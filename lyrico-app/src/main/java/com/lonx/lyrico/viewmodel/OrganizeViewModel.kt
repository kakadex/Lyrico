package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.lyrico.data.LyricoDatabase
import com.lonx.lyrico.data.model.entity.FolderEntity
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.domain.song.usecase.MoveSongResult
import com.lonx.lyrico.domain.song.usecase.MoveSongUseCase
import com.lonx.lyrico.utils.OrganizePlanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrganizeUiState(
    val scanning: Boolean = false,
    val items: List<OrganizePlanner.PlanItem> = emptyList(),
    val selectedUris: Set<String> = emptySet(),
    val organizing: Boolean = false,
    val movedCount: Int = 0,
    val failedCount: Int = 0,
    val skippedMediaStore: Int = 0,
    val skippedNoArtist: Int = 0,
    val skippedInPlace: Int = 0,
    val lastFinished: Boolean = false
) {
    val selectedCount: Int get() = selectedUris.size
}

/**
 * 目录整理（V0.09）：按「艺人/专辑」把授权文件夹里的歌曲归位。
 * 只处理文件夹授权（SAF）来源；系统媒体库来源与缺艺人信息的歌保留原位并明示计数。
 */
class OrganizeViewModel(
    private val songLibraryRepository: SongLibraryRepository,
    private val moveSongUseCase: MoveSongUseCase,
    private val database: LyricoDatabase
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrganizeUiState())
    val uiState: StateFlow<OrganizeUiState> = _uiState.asStateFlow()

    private var foldersById: Map<Long, FolderEntity> = emptyMap()

    fun scan() {
        if (_uiState.value.scanning) return
        viewModelScope.launch {
            _uiState.update { it.copy(scanning = true) }
            val songs = songLibraryRepository.observeSongs(SortBy.TITLE, SortOrder.ASC, null).first()
            val folders = database.folderDao().getAllFoldersOnce()
                .filter { it.addedBySaf && !it.treeUri.isNullOrBlank() }
            foldersById = folders.associateBy { it.id }
            val plan = OrganizePlanner.plan(
                songs = songs,
                rootPathByFolderId = folders.associate { it.id to it.path }
            )
            _uiState.update {
                it.copy(
                    scanning = false,
                    items = plan.items,
                    selectedUris = plan.items.mapTo(HashSet()) { item -> item.song.uri },
                    skippedMediaStore = plan.skippedMediaStore,
                    skippedNoArtist = plan.skippedNoArtist,
                    skippedInPlace = plan.skippedAlreadyInPlace
                )
            }
        }
    }

    fun toggle(uri: String) {
        _uiState.update { s ->
            val next = s.selectedUris.toMutableSet()
            if (!next.add(uri)) next.remove(uri)
            s.copy(selectedUris = next)
        }
    }

    /** 串行归位（同一目标目录的解析结果在用例内复用），完成后重新扫描刷新。 */
    fun organizeSelected() {
        if (_uiState.value.organizing) return
        viewModelScope.launch {
            val selected = _uiState.value.items.filter { it.song.uri in _uiState.value.selectedUris }
            if (selected.isEmpty()) return@launch
            _uiState.update { it.copy(organizing = true) }
            val dirIdByPath = mutableMapOf<String, String>()
            var moved = 0
            var failed = 0
            for (item in selected) {
                val folder = foldersById[item.song.folderId]
                val result = if (folder == null) {
                    MoveSongResult.Failed(IllegalStateException("找不到授权文件夹记录"))
                } else {
                    moveSongUseCase(item.song, item, folder, dirIdByPath)
                }
                if (result is MoveSongResult.Success) moved++ else failed++
            }
            _uiState.update {
                it.copy(
                    organizing = false,
                    movedCount = moved,
                    failedCount = failed,
                    lastFinished = true
                )
            }
            scan()
        }
    }
}
