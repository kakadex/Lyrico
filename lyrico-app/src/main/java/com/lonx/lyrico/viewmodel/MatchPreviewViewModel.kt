package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.match.ApplyMatchPreviewTaskConfig
import com.lonx.lyrico.data.model.match.MatchPreviewPayload
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.worker.BatchTaskScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** 预览页的一行：一首歌 + 它的匹配结果 + 用户勾了多少。 */
data class MatchPreviewRow(
    val itemId: String,
    val payload: MatchPreviewPayload,
    val selectedTargets: Set<MetadataFieldTarget>
) {
    val songUri: String get() = payload.songUri
    val allTargets: Set<MetadataFieldTarget> get() = payload.entries.map { it.target }.toSet()
    val isFullySelected: Boolean get() = allTargets.isNotEmpty() && selectedTargets == allTargets
}

data class MatchPreviewUiState(
    val loading: Boolean = true,
    val rows: List<MatchPreviewRow> = emptyList(),
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val expandedSongUris: Set<String> = emptySet(),
    val isApplying: Boolean = false,
    val appliedCount: Int = 0,
    val applyFinished: Boolean = false
) {
    val selectedSongCount: Int get() = rows.count { it.selectedTargets.isNotEmpty() }
    val selectedEntryCount: Int get() = rows.sumOf { it.selectedTargets.size }
}

/**
 * 预览 / 确认 / 写入。
 *
 * 预览阶段的明细就是主匹配任务里 `batch_task_items.resultJson` 的内容，
 * 所以这里只读不搜——网络请求在上一阶段已经全部做完了。
 */
class MatchPreviewViewModel(
    private val previewTaskId: String,
    private val batchTaskRepository: BatchTaskRepository,
    private val batchTaskScheduler: BatchTaskScheduler,
    private val songLibraryRepository: SongLibraryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MatchPreviewUiState())
    val uiState: StateFlow<MatchPreviewUiState> = _uiState.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        viewModelScope.launch {
            batchTaskRepository.observeItems(previewTaskId).collect { items ->
                val rows = items.mapNotNull { item ->
                    if (item.status != BatchTaskStatus.SUCCEEDED) return@mapNotNull null
                    val payload = item.resultJson?.let { raw ->
                        runCatching { json.decodeFromString<MatchPreviewPayload>(raw) }.getOrNull()
                    } ?: return@mapNotNull null
                    if (payload.entries.isEmpty()) return@mapNotNull null
                    MatchPreviewRow(
                        itemId = item.itemId,
                        payload = payload,
                        selectedTargets = payload.entries.map { it.target }.toSet()
                    )
                }
                val previous = _uiState.value.rows.associateBy { it.songUri }
                _uiState.update { state ->
                    state.copy(
                        loading = false,
                        rows = rows.map { row ->
                            // 重新收集时保留用户已经取消的勾选，不要被刷新覆盖掉
                            previous[row.songUri]?.let { old ->
                                row.copy(selectedTargets = old.selectedTargets intersect row.allTargets)
                            } ?: row
                        },
                        skippedCount = items.count { it.status == BatchTaskStatus.SKIPPED },
                        failedCount = items.count { it.status == BatchTaskStatus.FAILED }
                    )
                }
            }
        }
    }

    fun toggleExpand(songUri: String) {
        _uiState.update { state ->
            val expanded = state.expandedSongUris.toMutableSet()
            if (!expanded.add(songUri)) expanded.remove(songUri)
            state.copy(expandedSongUris = expanded)
        }
    }

    fun toggleSong(songUri: String, selected: Boolean) {
        _uiState.update { state ->
            state.copy(
                rows = state.rows.map { row ->
                    if (row.songUri != songUri) row
                    else row.copy(selectedTargets = if (selected) row.allTargets else emptySet())
                }
            )
        }
    }

    fun toggleField(songUri: String, target: MetadataFieldTarget) {
        _uiState.update { state ->
            state.copy(
                rows = state.rows.map { row ->
                    if (row.songUri != songUri) row
                    else {
                        val selected = row.selectedTargets.toMutableSet()
                        if (!selected.add(target)) selected.remove(target)
                        row.copy(selectedTargets = selected)
                    }
                }
            )
        }
    }

    fun setAll(selected: Boolean) {
        _uiState.update { state ->
            state.copy(
                rows = state.rows.map { row ->
                    row.copy(selectedTargets = if (selected) row.allTargets else emptySet())
                }
            )
        }
    }

    fun apply() {
        val state = _uiState.value
        if (state.isApplying) return

        val selections = state.rows
            .mapNotNull { row ->
                val entries = row.payload.entries.filter { it.target in row.selectedTargets }
                if (entries.isEmpty()) null else row.songUri to entries
            }
            .toMap()
        if (selections.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isApplying = true) }

            val songs: List<SongEntity> = selections.keys
                .mapNotNull { uri -> songLibraryRepository.getSongByUri(uri) }
            if (songs.isEmpty()) {
                _uiState.update { it.copy(isApplying = false) }
                return@launch
            }

            val configJson = json.encodeToString(
                ApplyMatchPreviewTaskConfig.serializer(),
                ApplyMatchPreviewTaskConfig(entries = selections)
            )
            val taskId = batchTaskRepository.createTask(
                type = BatchTaskType.APPLY_MATCH_PREVIEW,
                songs = songs,
                configJson = configJson
            )
            batchTaskScheduler.enqueue(taskId)

            batchTaskRepository.observeTask(taskId).filterNotNull().collect { task ->
                val terminal = task.status == BatchTaskStatus.SUCCEEDED ||
                        task.status == BatchTaskStatus.FAILED ||
                        task.status == BatchTaskStatus.CANCELLED
                if (terminal) {
                    _uiState.update {
                        it.copy(
                            isApplying = false,
                            applyFinished = true,
                            appliedCount = task.successCount
                        )
                    }
                    return@collect
                }
                _uiState.update { it.copy(appliedCount = task.current) }
            }
        }
    }
}
