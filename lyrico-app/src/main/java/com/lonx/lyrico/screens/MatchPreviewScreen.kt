@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.lonx.lyrico.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.R
import com.lonx.lyrico.data.model.UiEngine
import com.lonx.lyrico.data.model.match.MatchPreviewEntry
import com.lonx.lyrico.data.model.match.snippet
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import com.lonx.lyrico.ui.components.icons.LucideIcons
import com.lonx.lyrico.ui.components.library.EmptyStateKind
import com.lonx.lyrico.ui.components.library.IllustratedEmptyState
import com.lonx.lyrico.ui.components.library.LibraryDetailPage
import com.lonx.lyrico.ui.components.salt.SaltIconButton
import com.lonx.lyrico.ui.components.scaffoldTopHorizontalPadding
import com.lonx.lyrico.ui.theme.LocalUiEngine
import com.lonx.lyrico.viewmodel.MatchPreviewRow
import com.lonx.lyrico.viewmodel.MatchPreviewUiState
import com.lonx.lyrico.viewmodel.MatchPreviewViewModel
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.icons.Check
import com.moriafly.salt.ui.icons.SaltIcons
import com.moriafly.salt.ui.icons.Uncheck
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 匹配结果确认页。
 *
 * 数据全部来自上一个匹配任务的 `resultJson`（内容是 `MatchPreviewPayload`），这里不再联网：
 * 用户能做的只有「勾 / 不勾」，点写入才真正产生一个 APPLY_MATCH_PREVIEW 任务去落盘。
 */
@Destination<RootGraph>(route = "match_preview")
@Composable
fun MatchPreviewScreen(
    taskId: String,
    navigator: DestinationsNavigator
) {
    val viewModel: MatchPreviewViewModel = koinViewModel { parametersOf(taskId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val salt = LocalUiEngine.current == UiEngine.SALT

    LibraryDetailPage(
        title = stringResource(R.string.match_preview_title),
        onBack = { navigator.popBackStack() },
        actions = {
            SaltIconButton(
                icon = LucideIcons.ListChecks,
                label = stringResource(R.string.match_preview_select_all),
                onClick = { viewModel.setAll(true) }
            )
        },
        miuixTopBar = {
            SmallTopAppBar(
                title = stringResource(R.string.match_preview_title),
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(
                            imageVector = LucideIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.setAll(true) }) {
                        Icon(
                            imageVector = LucideIcons.ListChecks,
                            contentDescription = stringResource(R.string.match_preview_select_all)
                        )
                    }
                },
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    if (salt) com.lonx.lyrico.ui.components.scaffoldContentPadding(paddingValues)
                    else scaffoldTopHorizontalPadding(paddingValues)
                )
        ) {
            PreviewSummaryCard(state = state)

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (salt) Modifier else Modifier
                            .scrollEndHaptic()
                            .overScrollVertical()
                            .nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                overscrollEffect = if (salt) rememberOverscrollEffect() else null,
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                if (state.loading) {
                    return@LazyColumn
                }
                if (state.rows.isEmpty()) {
                    item {
                        IllustratedEmptyState(
                            kind = EmptyStateKind.Tasks,
                            title = stringResource(R.string.match_preview_empty),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    items(state.rows, key = { it.itemId }) { row ->
                        MatchRowCard(
                            row = row,
                            expanded = row.songUri in state.expandedSongUris,
                            onToggleExpand = { viewModel.toggleExpand(row.songUri) },
                            onToggleSong = { selected -> viewModel.toggleSong(row.songUri, selected) },
                            onToggleField = { target -> viewModel.toggleField(row.songUri, target) }
                        )
                    }
                }
            }

            PreviewFooter(
                state = state,
                onApply = viewModel::apply,
                onClose = { navigator.popBackStack() }
            )
        }
    }
}

@Composable
private fun PreviewSummaryCard(
    state: MatchPreviewUiState
) {
    val summary = stringResource(
        R.string.match_preview_summary,
        state.rows.size,
        state.skippedCount + state.failedCount
    )
    val detail = stringResource(R.string.match_preview_apply_count, state.selectedSongCount)

    if (LocalUiEngine.current == UiEngine.SALT) {
        com.moriafly.salt.ui.Card(modifier = Modifier.padding(bottom = 12.dp)) {
            com.moriafly.salt.ui.ItemValue(text = summary, sub = detail)
        }
        return
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
    ) {
        BasicComponent(title = summary, summary = detail)
    }
}

@Composable
private fun MatchRowCard(
    row: MatchPreviewRow,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleSong: (Boolean) -> Unit,
    onToggleField: (MetadataFieldTarget) -> Unit
) {
    val salt = LocalUiEngine.current == UiEngine.SALT
    val title = row.payload.songTitle.ifBlank { row.songUri }
    val scorePercent = (row.payload.score * 100).toInt().coerceIn(0, 100)
    val candidate = listOf(row.payload.candidateTitle, row.payload.candidateArtist)
        .filter { it.isNotBlank() }
        .joinToString(" - ")
        .ifBlank { "—" }
    val summary = buildString {
        append(candidate)
        append(" · ")
        append(
            stringResource(
                R.string.match_preview_score_format,
                row.payload.sourceName.ifBlank { row.payload.sourceId },
                scorePercent
            )
        )
        if (row.payload.isLowConfidence) {
            append(" · ")
            append(stringResource(R.string.match_preview_low_confidence))
        }
    }

    if (salt) {
        com.moriafly.salt.ui.Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleExpand)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(44.dp).clickable { onToggleSong(!row.isFullySelected) },
                        contentAlignment = Alignment.Center
                    ) {
                        com.moriafly.salt.ui.Icon(
                            painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(
                                if (row.isFullySelected) SaltIcons.Check else SaltIcons.Uncheck
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = SaltTheme.colors.highlight
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                        com.moriafly.salt.ui.Text(
                            text = title,
                            style = SaltTheme.textStyles.main,
                            color = SaltTheme.colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        com.moriafly.salt.ui.Text(
                            text = summary,
                            style = SaltTheme.textStyles.sub,
                            color = SaltTheme.colors.subText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (expanded) {
                    row.payload.entries.forEach { entry ->
                        val target = entry.target
                        com.moriafly.salt.ui.ItemCheck(
                            state = target in row.selectedTargets,
                            onChange = { onToggleField(target) },
                            text = stringResource(target.labelRes),
                            sub = entry.toDisplayText()
                        )
                    }
                }
            }
        }
        return
    }

    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    state = if (row.isFullySelected) ToggleableState.On else ToggleableState.Off,
                    onClick = { onToggleSong(!row.isFullySelected) }
                )
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        text = title,
                        style = MiuixTheme.textStyles.main,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = summary,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (expanded) {
                row.payload.entries.forEach { entry ->
                    val target = entry.target
                    CheckboxPreference(
                        title = stringResource(target.labelRes),
                        summary = entry.toDisplayText(),
                        checked = target in row.selectedTargets,
                        onCheckedChange = { onToggleField(target) },
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MatchPreviewEntry.toDisplayText(): String {
    val empty = stringResource(R.string.match_preview_empty_value)
    val oldText = oldValue?.takeIf { it.isNotBlank() }?.let { snippet(it, 80) } ?: empty
    val newText = when (target) {
        MetadataFieldTarget.LYRICS -> {
            val lines = newValue.lines().count { it.isNotBlank() }
            snippet(newValue, 80) + "\n($lines)"
        }

        MetadataFieldTarget.COVER -> newValue
        else -> snippet(newValue, 80)
    }
    return stringResource(R.string.match_preview_old_value) + ": " + oldText + "\n" +
            stringResource(R.string.match_preview_new_value) + ": " + newText
}

@Composable
private fun PreviewFooter(
    state: MatchPreviewUiState,
    onApply: () -> Unit,
    onClose: () -> Unit
) {
    val salt = LocalUiEngine.current == UiEngine.SALT
    val canApply = state.selectedSongCount > 0 && !state.isApplying
    val label = when {
        state.applyFinished -> stringResource(R.string.action_close)
        state.isApplying -> stringResource(R.string.batch_edit_processing)
        else -> stringResource(R.string.match_preview_apply_count, state.selectedSongCount)
    }
    val onClick = when {
        state.applyFinished -> onClose
        state.isApplying -> ({})
        else -> onApply
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (salt) {
            com.moriafly.salt.ui.Button(
                onClick = { if (canApply || state.applyFinished) onClick() },
                text = label,
                modifier = Modifier.fillMaxWidth(),
                enabled = canApply || state.applyFinished,
            )
        } else {
            Button(
                onClick = { if (canApply || state.applyFinished) onClick() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(text = label, style = MiuixTheme.textStyles.button)
            }
        }
    }
}
