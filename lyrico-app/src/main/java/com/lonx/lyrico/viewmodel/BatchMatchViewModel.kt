package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.lyrico.data.SharedSelectionManager
import com.lonx.lyrico.data.model.BatchMatchConfig
import com.lonx.lyrico.data.model.BatchMatchConfigDefaults
import com.lonx.lyrico.data.model.BatchTaskStatus
import com.lonx.lyrico.data.model.BatchTaskType
import com.lonx.lyrico.data.model.lyrics.LyricRenderConfig
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.repository.BatchTaskRepository
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.plugin.source.SearchSourceProvider
import com.lonx.lyrico.worker.BatchTaskScheduler
import com.lonx.lyrico.worker.processor.MatchMetadataTaskConfig
import com.lonx.lyrico.data.model.lyrics.SourceRuntimeConfig
import com.lonx.lyrico.data.model.lyrics.SearchSource
import com.lonx.lyrico.data.model.lyrics.SongSearchResult
import com.lonx.lyrico.utils.MusicMatchUtils
import com.lonx.lyrico.data.model.plugin.PluginSourceType
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import com.lonx.lyrico.data.model.metadata.MetadataWriteMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.lonx.lyrico.data.editfield.EditFieldConfigRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

enum class BatchMatchType(
    val sourceType: PluginSourceType,
    val taskType: BatchTaskType,
    val targets: Set<MetadataFieldTarget>
) {
    METADATA(
        sourceType = PluginSourceType.METADATA,
        taskType = BatchTaskType.MATCH_METADATA,
        targets = MetadataFieldTarget.entries.toSet() -
            MetadataFieldTarget.LYRICS - MetadataFieldTarget.COVER
    ),

    /**
     * 标签 + 歌词 + 封面一次跑完。
     *
     * 复用 MATCH_METADATA 的搜索主干（一次搜索拿到候选，歌词/封面再按需补取），
     * 但用独立的任务类型，避免历史记录里「全部匹配」被记成「标签匹配」。
     * ReplayGain 与自定义字段不在此列：它们不是联网源能提供的。
     */
    ALL(
        sourceType = PluginSourceType.METADATA,
        taskType = BatchTaskType.MATCH_ALL,
        targets = MetadataFieldTarget.entries.toSet() -
            MetadataFieldTarget.REPLAY_GAIN_TRACK_GAIN -
            MetadataFieldTarget.REPLAY_GAIN_TRACK_PEAK -
            MetadataFieldTarget.REPLAY_GAIN_ALBUM_GAIN -
            MetadataFieldTarget.REPLAY_GAIN_ALBUM_PEAK -
            MetadataFieldTarget.REPLAY_GAIN_REFERENCE_LOUDNESS -
            MetadataFieldTarget.CUSTOM
    ),
    LYRICS(
        sourceType = PluginSourceType.LYRICS,
        taskType = BatchTaskType.MATCH_LYRICS,
        targets = setOf(MetadataFieldTarget.LYRICS)
    ),
    COVER(
        sourceType = PluginSourceType.COVER,
        taskType = BatchTaskType.MATCH_COVER,
        targets = setOf(MetadataFieldTarget.COVER)
    )
}

data class BatchMatchUiState(
    val showBatchConfigDialog: Boolean = false,
    val matchType: BatchMatchType = BatchMatchType.METADATA,
    val isRunning: Boolean = false,
    val batchProgress: Pair<Int, Int>? = null,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val skippedCount: Int = 0,
    val currentFile: String = "",
    val batchTimeMillis: Long = 0,
    val currentTaskId: String? = null,
    val fileProgressMap: Map<String, Float> = emptyMap(),
    /** 非空表示「只匹配未写入」，界面应跳到确认页；由 [BatchMatchViewModel.consumePreviewNavigation] 消费。 */
    val previewTaskId: String? = null,
)

class BatchMatchViewModel(
    private val settingsRepository: SettingsRepository,
    private val selectionManager: SharedSelectionManager,
    private val batchTaskRepository: BatchTaskRepository,
    private val batchTaskScheduler: BatchTaskScheduler,
    private val editFieldConfigRepository: EditFieldConfigRepository,
    private val searchSourceProvider: SearchSourceProvider,
    private val songLibraryRepository: SongLibraryRepository
) : ViewModel() {

    val visibleTargets = editFieldConfigRepository.configFlow.map { it.matchTargets() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val batchMatchConfig: StateFlow<BatchMatchConfig> = settingsRepository.batchMatchConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, BatchMatchConfigDefaults.DEFAULT_CONFIG)

    private val sourcesByType: StateFlow<Map<BatchMatchType, List<SearchSource>>> = combine(
        searchSourceProvider.observeSources(PluginSourceType.METADATA),
        searchSourceProvider.observeSources(PluginSourceType.LYRICS),
        searchSourceProvider.observeSources(PluginSourceType.COVER)
    ) { metadata, lyrics, cover ->
        mapOf(
            BatchMatchType.METADATA to metadata,
            BatchMatchType.ALL to metadata,
            BatchMatchType.LYRICS to lyrics,
            BatchMatchType.COVER to cover
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val sourceSettings: StateFlow<Map<String, SourceRuntimeConfig>> =
        settingsRepository.sourceSettingsByIdFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    private val separator: StateFlow<String> = settingsRepository.separator
        .stateIn(viewModelScope, SharingStarted.Eagerly, "/")
    private val lyricRenderConfig: StateFlow<LyricRenderConfig?> = settingsRepository.lyricRenderConfigFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _uiState = MutableStateFlow(BatchMatchUiState())
    val uiState: StateFlow<BatchMatchUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null

    /** 非多选入口待匹配的歌（单首 / 搜索结果配对结果）；多选流程下为空。 */
    private var pendingSongs: List<SongEntity> = emptyList()

    init {
        viewModelScope.launch {
            var runningTask: BatchTaskEntity? = null
            for (type in BatchMatchType.entries) {
                runningTask = batchTaskRepository.getRunningTaskByType(type.taskType)
                if (runningTask != null) break
            }
            if (runningTask != null) {
                resumeObservingTask(runningTask.taskId)
            }
        }
    }

    private fun resumeObservingTask(taskId: String) {
        observeJob?.cancel()
        _uiState.update {
            it.copy(
                currentTaskId = taskId,
                isRunning = true,
                batchProgress = 0 to 0
            )
        }
        observeJob = viewModelScope.launch {
            combine(
                batchTaskRepository.observeTask(taskId),
                batchTaskRepository.observeItems(taskId)
            ) { task, items ->
                task to items
            }.collect { (task, items) ->
                if (task == null) return@collect
                val progressMap = items
                    .filter { it.status == BatchTaskStatus.RUNNING && it.progress != null }
                    .associate { it.fileName to it.progress!! }
                _uiState.update {
                    it.copy(
                        batchProgress = task.current to task.total,
                        successCount = task.successCount,
                        failureCount = task.failureCount,
                        skippedCount = task.skippedCount,
                        currentFile = task.currentFile ?: "",
                        isRunning = task.status == BatchTaskStatus.RUNNING || task.status == BatchTaskStatus.QUEUED,
                        fileProgressMap = progressMap
                    )
                }
                if (task.status == BatchTaskStatus.SUCCEEDED ||
                    task.status == BatchTaskStatus.FAILED ||
                    task.status == BatchTaskStatus.CANCELLED
                ) {
                    val duration = if (task.startedAt != null && task.finishedAt != null) {
                        task.finishedAt - task.startedAt
                    } else 0L
                    // 预览模式跑完不写盘，直接把用户送到确认页；写入与否交给用户在预览页决定。
                    val previewOnly = task.configJson?.let { raw ->
                        runCatching {
                            Json.decodeFromString<MatchMetadataTaskConfig>(raw).previewOnly
                        }.getOrDefault(false)
                    } ?: false
                    _uiState.update {
                        it.copy(
                            isRunning = false,
                            batchTimeMillis = duration,
                            fileProgressMap = emptyMap(),
                            previewTaskId = taskId.takeIf { previewOnly }
                        )
                    }
                    observeJob?.cancel()
                }
            }
        }
    }

    fun openBatchMatchConfig(matchType: BatchMatchType) {
        pendingSongs = emptyList()
        val selectedIds = selectionManager.selectedUris.value
        if (selectedIds.isNotEmpty()) {
            _uiState.update {
                it.copy(showBatchConfigDialog = true, matchType = matchType)
            }
        }
    }

    /**
     * 不经过多选模式的入口：单首（编辑页 / 单曲菜单）或已经算好的一组歌（搜索结果配对）。
     *
     * 与 [openBatchMatchConfig] 的区别只有「要匹配哪些歌」从哪来 —— 后续配置、执行、预览完全共用。
     */
    fun openBatchMatchConfigFor(matchType: BatchMatchType, songs: List<SongEntity>) {
        if (songs.isEmpty()) return
        pendingSongs = songs
        _uiState.update { it.copy(showBatchConfigDialog = true, matchType = matchType) }
    }

    fun openBatchMatchConfigForSong(matchType: BatchMatchType, songUri: String) {
        viewModelScope.launch {
            val song = songLibraryRepository.getSongByUri(songUri) ?: return@launch
            openBatchMatchConfigFor(matchType, listOf(song))
        }
    }

    /**
     * 在线搜索结果多选 → 批量匹配。
     *
     * 搜索结果本身不是本地歌曲，没有 uri，所以这里先做一次「远程候选 → 本地曲库」的反向配对：
     * 用同一套 `MusicMatchUtils` 打分，取每条的本地最佳，低于 [REMOTE_LOCAL_MIN_SCORE] 的丢弃。
     * 之后完全复用普通匹配流程（同样的配置弹层、同样的预览确认页）。
     */
    fun openBatchMatchConfigForRemote(
        matchType: BatchMatchType,
        results: List<SongSearchResult>
    ) {
        if (results.isEmpty()) return
        viewModelScope.launch {
            val allSongs = songLibraryRepository
                .observeSongs(SortBy.TITLE, SortOrder.ASC)
                .first()
            if (allSongs.isEmpty()) return@launch

            val matched = mutableListOf<SongEntity>()
            val used = mutableSetOf<String>()
            results.forEach { result ->
                val best = allSongs.asSequence()
                    .filter { it.uri !in used }
                    .map { song ->
                        song to MusicMatchUtils.calculateMatchScore(
                            result = result,
                            song = song,
                            preferFileName = false
                        )
                    }
                    .maxByOrNull { it.second }
                if (best != null && best.second >= REMOTE_LOCAL_MIN_SCORE) {
                    used += best.first.uri
                    matched += best.first
                }
            }
            if (matched.isNotEmpty()) {
                openBatchMatchConfigFor(matchType, matched)
            }
        }
    }

    fun closeBatchMatchConfig() {
        pendingSongs = emptyList()
        _uiState.update { it.copy(showBatchConfigDialog = false) }
    }

    fun saveBatchMatchConfig(matchConfig: BatchMatchConfig) {
        viewModelScope.launch {
            settingsRepository.saveBatchMatchConfig(matchConfig)
        }
    }

    fun batchMatch(songs: List<SongEntity>, matchConfig: BatchMatchConfig) {
        closeBatchMatchConfig()

        _uiState.update {
            it.copy(
                isRunning = true,
                successCount = 0,
                failureCount = 0,
                skippedCount = 0,
                batchProgress = 0 to 0,
                batchTimeMillis = 0,
                fileProgressMap = emptyMap()
            )
        }

        viewModelScope.launch {
            // 两个来源：显式指定的歌优先（单首/搜索结果配对），否则退回多选集合 ∩ 当前列表。
            val explicit = pendingSongs
            pendingSongs = emptyList()
            val songsToMatch = if (explicit.isNotEmpty()) {
                explicit
            } else {
                val selectedIds = selectionManager.selectedUris.value
                if (selectedIds.isEmpty()) {
                    _uiState.update { it.copy(isRunning = false) }
                    return@launch
                }
                songs.filter { it.uri in selectedIds }
            }
            if (songsToMatch.isEmpty()) {
                _uiState.update { it.copy(isRunning = false) }
                return@launch
            }

            val matchType = _uiState.value.matchType
            val allowedTargets = editFieldConfigRepository.configFlow.first().matchTargets().toSet()
            val effectiveConfig = matchConfig.restrictedTo(allowedTargets intersect matchType.targets)
            val currentOrderIds = buildEnabledSourceOrderIds(matchType)
            val configJson = Json.encodeToString(
                MatchMetadataTaskConfig.serializer(),
                MatchMetadataTaskConfig(
                    matchConfig = effectiveConfig,
                    separator = separator.value,
                    enabledSourceOrderIds = currentOrderIds,
                    sourceSettings = sourceSettings.value.mapValues { it.value.values },
                    lyricRenderConfig = lyricRenderConfig.value,
                    concurrency = matchConfig.concurrency,
                    previewOnly = matchConfig.previewFirst
                )
            )
            val taskId = batchTaskRepository.createTask(
                type = matchType.taskType,
                songs = songsToMatch,
                configJson = configJson
            )
            batchTaskScheduler.enqueue(taskId)
            resumeObservingTask(taskId)
        }
    }

    fun abortBatchMatch() {
        val taskId = _uiState.value.currentTaskId
        if (taskId != null) {
            batchTaskScheduler.cancel(taskId)
            viewModelScope.launch {
                batchTaskRepository.markCancelled(taskId)
            }
        }
    }

    fun closeBatchMatchDialog() {
        _uiState.update {
            it.copy(
                batchProgress = null,
                currentFile = "",
                isRunning = false,
                batchTimeMillis = 0,
                fileProgressMap = emptyMap(),
                previewTaskId = null
            )
        }
    }

    /** 确认页已经跳转，清掉一次性信号，避免返回时又跳一次。 */
    fun consumePreviewNavigation() {
        _uiState.update { it.copy(previewTaskId = null) }
    }
    fun clearFinishedTasks() {
        _uiState.update {
            it.copy(
                batchProgress = null,
                currentFile = "",
                isRunning = false,
                batchTimeMillis = 0,
                fileProgressMap = emptyMap()
            )
        }
    }

    private fun buildEnabledSourceOrderIds(matchType: BatchMatchType): List<String> {
        return sourcesByType.value[matchType].orEmpty().map { it.id }
    }

    private companion object {
        /** 远程候选 ↔ 本地歌曲的配对准入门槛；比正规匹配低一些，最终勾选权在预览页。 */
        const val REMOTE_LOCAL_MIN_SCORE = 0.50
    }
}
