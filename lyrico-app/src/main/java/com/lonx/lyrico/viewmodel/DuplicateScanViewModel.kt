package com.lonx.lyrico.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.domain.song.usecase.DeleteSongsUseCase
import com.lonx.lyrico.utils.DuplicateDetector
import com.lonx.lyrico.utils.DuplicateGroup
import com.lonx.lyrico.utils.DuplicateItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DuplicateScanUiState(
    val scanning: Boolean = false,
    val scannedCount: Int = 0,
    val groups: List<DuplicateGroup> = emptyList(),
    val selectedForDeletion: Set<String> = emptySet(),
    val deleting: Boolean = false
) {
    val selectedCount: Int get() = selectedForDeletion.size
}

class DuplicateScanViewModel(
    private val songLibraryRepository: SongLibraryRepository,
    private val deleteSongsUseCase: DeleteSongsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(DuplicateScanUiState())
    val uiState: StateFlow<DuplicateScanUiState> = _uiState.asStateFlow()

    /** 扫描整个曲库，按文件名找出重复分组，默认勾选「除建议保留外的所有重复项」。 */
    fun scan() {
        if (_uiState.value.scanning) return
        viewModelScope.launch {
            _uiState.update { it.copy(scanning = true) }
            val songs = songLibraryRepository.observeSongs(SortBy.TITLE, SortOrder.ASC, null).first()
            val (groups, selected) = withContext(Dispatchers.Default) {
                val items = songs.map { song ->
                    DuplicateItem(
                        id = song.uri,
                        fileName = song.fileName,
                        size = song.fileSize,
                        relativePath = song.filePath
                    )
                }
                val groups = DuplicateDetector.detect(items)
                val selected = HashSet<String>()
                for (group in groups) {
                    val keep = DuplicateDetector.recommendKeep(group)
                    group.files.forEachIndexed { i, f -> if (i != keep) selected.add(f.id) }
                }
                groups to selected
            }
            _uiState.update {
                it.copy(
                    scanning = false,
                    scannedCount = songs.size,
                    groups = groups,
                    selectedForDeletion = selected
                )
            }
        }
    }

    fun toggle(id: String) {
        _uiState.update { s ->
            val next = s.selectedForDeletion.toMutableSet()
            if (!next.add(id)) next.remove(id)
            s.copy(selectedForDeletion = next)
        }
    }

    /** 删除所有勾选的重复文件，成功后重新扫描刷新分组。 */
    fun deleteSelected() {
        if (_uiState.value.deleting) return
        viewModelScope.launch {
            val ids = _uiState.value.selectedForDeletion
            if (ids.isEmpty()) return@launch
            _uiState.update { it.copy(deleting = true) }
            val songs = songLibraryRepository.getSongsByUris(ids.toList())
            deleteSongsUseCase(songs)
            _uiState.update { it.copy(deleting = false, selectedForDeletion = emptySet()) }
            scan()
        }
    }
}
