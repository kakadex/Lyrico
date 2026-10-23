package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.domain.song.usecase.RenameSongResult
import com.lonx.lyrico.domain.song.usecase.RenameSongUseCase
import com.lonx.lyrico.utils.FileNameParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一条改名计划：旧名 → 新名（去污染后的 stem + 原扩展名）。 */
data class FileNameCleanupItem(
    val song: SongEntity,
    val oldStem: String,
    val newStem: String
) {
    val extension: String
        get() = song.fileName.substringAfterLast('.', "")
    val newFileName: String
        get() = if (extension.isBlank()) newStem else "$newStem.$extension"
    val oldFileName: String get() = song.fileName
}

data class FileNameCleanupUiState(
    val scanning: Boolean = false,
    val items: List<FileNameCleanupItem> = emptyList(),
    val selectedUris: Set<String> = emptySet(),
    val renaming: Boolean = false,
    val renamedCount: Int = 0,
    val failedCount: Int = 0,
    val lastFinished: Boolean = false
) {
    val selectedCount: Int get() = selectedUris.size
}

/**
 * 文件名清理（V0.08）：扫描曲库，用 [FileNameParser.stripNameNoise] 去掉文件名里的
 * 污染后缀 / 音轨前缀 / 首尾书名号方括号 / 多余空格，列出「旧名 → 新名」计划，
 * 确认后走 [RenameSongUseCase] 逐个改名（文件 + 库记录同步更新）。
 */
class FileNameCleanupViewModel(
    private val songLibraryRepository: SongLibraryRepository,
    private val renameSongUseCase: RenameSongUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileNameCleanupUiState())
    val uiState: StateFlow<FileNameCleanupUiState> = _uiState.asStateFlow()

    fun scan() {
        if (_uiState.value.scanning) return
        viewModelScope.launch {
            _uiState.update { it.copy(scanning = true) }
            val songs = songLibraryRepository.observeSongs(SortBy.TITLE, SortOrder.ASC, null).first()
            val items = withContext(Dispatchers.Default) {
                songs.mapNotNull { song ->
                    val stem = song.fileName.substringBeforeLast('.', song.fileName).trim()
                    if (stem.isBlank()) return@mapNotNull null
                    val cleaned = FileNameParser.stripNameNoise(stem)
                    if (cleaned.isBlank() || cleaned == stem) return@mapNotNull null
                    FileNameCleanupItem(song = song, oldStem = stem, newStem = cleaned)
                }
            }
            _uiState.update {
                it.copy(
                    scanning = false,
                    items = items,
                    selectedUris = items.mapTo(HashSet()) { item -> item.song.uri }
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

    /** 串行改名（避免同目录竞争），完成后重新扫描刷新计划。 */
    fun renameSelected() {
        if (_uiState.value.renaming) return
        viewModelScope.launch {
            val selected = _uiState.value.items.filter { it.song.uri in _uiState.value.selectedUris }
            if (selected.isEmpty()) return@launch
            _uiState.update { it.copy(renaming = true) }
            var ok = 0
            var failed = 0
            for (item in selected) {
                val result = renameSongUseCase(item.song, item.newFileName)
                if (result is RenameSongResult.Success) ok++ else failed++
            }
            _uiState.update {
                it.copy(
                    renaming = false,
                    renamedCount = ok,
                    failedCount = failed,
                    lastFinished = true
                )
            }
            scan()
        }
    }
}
