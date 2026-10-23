package com.lonx.lyrico.ui.components.batch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.viewmodel.BatchMatchViewModel
import com.ramcosta.composedestinations.generated.destinations.MatchPreviewDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator

/**
 * 批量匹配的界面宿主：配置弹层 + 进度弹层 + 「跑完跳确认页」。
 *
 * 抽出来是因为现在有三个入口要用它（多选操作栏、编辑页单首、搜索结果批量），
 * 而它们只差「要匹配哪些歌」，配置/进度/预览的接线完全一样。
 *
 * @param songs 多选模式下用来把选中 uri 还原成歌曲的候选池；显式指定歌曲的入口传空列表即可。
 */
@Composable
fun MatchFlowHost(
    viewModel: BatchMatchViewModel,
    navigator: DestinationsNavigator,
    songs: List<SongEntity> = emptyList()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val targets by viewModel.visibleTargets.collectAsStateWithLifecycle()
    val config by viewModel.batchMatchConfig.collectAsStateWithLifecycle()

    BatchMatchConfigBottomSheet(
        show = uiState.showBatchConfigDialog,
        matchType = uiState.matchType,
        initialConfig = config,
        visibleTargets = targets,
        onDismissRequest = { saved ->
            viewModel.saveBatchMatchConfig(saved)
            viewModel.closeBatchMatchConfig()
        },
        onConfirm = { saved ->
            viewModel.saveBatchMatchConfig(saved)
            viewModel.batchMatch(songs, saved)
        }
    )

    BatchMatchBottomSheet(
        onDismissRequest = {
            if (!uiState.isRunning) viewModel.closeBatchMatchDialog()
        },
        onDismissFinished = {
            if (!uiState.isRunning) viewModel.closeBatchMatchDialog()
        },
        enableNestedScroll = false,
        uiState = uiState,
        onAbort = { viewModel.abortBatchMatch() }
    )

    val previewTaskId = uiState.previewTaskId
    LaunchedEffect(previewTaskId) {
        if (previewTaskId != null) {
            viewModel.consumePreviewNavigation()
            viewModel.closeBatchMatchDialog()
            navigator.navigate(MatchPreviewDestination(previewTaskId))
        }
    }
}
