@file:OptIn(com.moriafly.salt.ui.UnstableSaltUiApi::class)

package com.lonx.lyrico.screens

import android.text.format.Formatter
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lonx.lyrico.R
import com.lonx.lyrico.data.model.UiEngine
import com.lonx.lyrico.ui.components.icons.LucideIcons
import com.lonx.lyrico.ui.components.library.EmptyStateKind
import com.lonx.lyrico.ui.components.library.IllustratedEmptyState
import com.lonx.lyrico.ui.components.library.LibraryDetailPage
import com.lonx.lyrico.ui.components.salt.SaltConfirmDialog
import com.lonx.lyrico.ui.components.salt.SaltIconButton
import com.lonx.lyrico.ui.components.scaffoldTopHorizontalPadding
import com.lonx.lyrico.ui.theme.LocalUiEngine
import com.lonx.lyrico.viewmodel.FileNameCleanupItem
import com.lonx.lyrico.viewmodel.FileNameCleanupUiState
import com.lonx.lyrico.viewmodel.FileNameCleanupViewModel
import com.moriafly.salt.ui.SaltTheme
import com.moriafly.salt.ui.icons.Check
import com.moriafly.salt.ui.icons.SaltIcons
import com.moriafly.salt.ui.icons.Uncheck
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 文件名清理（V0.08）：扫描曲库，把「广告前缀 / 音轨序号 / 首尾书名号方括号 /
 * 多余空格」污染的文件名列成改名计划，勾选确认后批量重命名。
 */
@Destination<RootGraph>(route = "filename_cleanup")
@Composable
fun FileNameCleanupScreen(
    navigator: DestinationsNavigator
) {
    val viewModel: FileNameCleanupViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val salt = LocalUiEngine.current == UiEngine.SALT
    val context = LocalContext.current
    var showConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.scan() }

    if (showConfirm) {
        if (salt) {
            SaltConfirmDialog(
                title = stringResource(R.string.filename_cleanup_rename_title),
                summary = stringResource(R.string.filename_cleanup_rename_count, state.selectedCount),
                onDismissRequest = { showConfirm = false },
                onConfirm = { viewModel.renameSelected(); showConfirm = false },
                cancelText = stringResource(R.string.cancel),
                confirmText = stringResource(R.string.confirm),
            )
        } else {
            WindowDialog(
                title = stringResource(R.string.filename_cleanup_rename_title),
                show = showConfirm,
                onDismissRequest = { showConfirm = false }
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.filename_cleanup_rename_count, state.selectedCount),
                        modifier = Modifier.fillMaxWidth(),
                        color = MiuixTheme.colorScheme.onBackground
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TextButton2(
                            text = stringResource(R.string.cancel),
                            onClick = { showConfirm = false },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton2(
                            text = stringResource(R.string.confirm),
                            onClick = { viewModel.renameSelected(); showConfirm = false },
                            modifier = Modifier.weight(1f),
                            primary = true
                        )
                    }
                }
            }
        }
    }

    LibraryDetailPage(
        title = stringResource(R.string.filename_cleanup_title),
        onBack = { navigator.popBackStack() },
        actions = {
            SaltIconButton(
                icon = LucideIcons.RotateCcw,
                label = stringResource(R.string.filename_cleanup_title),
                enabled = !state.scanning,
                onClick = { viewModel.scan() }
            )
        },
        miuixTopBar = {
            SmallTopAppBar(
                title = stringResource(R.string.filename_cleanup_title),
                navigationIcon = {
                    IconButton(onClick = { navigator.popBackStack() }) {
                        Icon(
                            imageVector = LucideIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(enabled = !state.scanning, onClick = { viewModel.scan() }) {
                        Icon(
                            imageVector = LucideIcons.RotateCcw,
                            contentDescription = stringResource(R.string.filename_cleanup_title)
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
            if (state.lastFinished) {
                ResultBanner(
                    text = stringResource(
                        R.string.filename_cleanup_result,
                        state.renamedCount,
                        state.failedCount
                    )
                )
            }

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
                if (state.scanning) {
                    item {
                        IllustratedEmptyState(
                            kind = EmptyStateKind.Tasks,
                            title = stringResource(R.string.duplicate_scan_scanning),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (state.items.isEmpty()) {
                    item {
                        IllustratedEmptyState(
                            kind = EmptyStateKind.Tasks,
                            title = stringResource(R.string.filename_cleanup_no_pending),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    items(state.items, key = { it.song.uri }) { item ->
                        CleanupRowCard(
                            item = item,
                            selected = item.song.uri in state.selectedUris,
                            onToggle = { viewModel.toggle(item.song.uri) }
                        )
                    }
                }
            }

            CleanupFooter(
                state = state,
                onRename = { showConfirm = true }
            )
        }
    }
}

@Composable
private fun TextButton2(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false
) {
    if (primary) {
        top.yukonga.miuix.kmp.basic.TextButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
    } else {
        top.yukonga.miuix.kmp.basic.TextButton(
            text = text,
            onClick = onClick,
            modifier = modifier
        )
    }
}

@Composable
private fun ResultBanner(text: String) {
    val salt = LocalUiEngine.current == UiEngine.SALT
    if (salt) {
        com.moriafly.salt.ui.Card(modifier = Modifier.padding(bottom = 8.dp)) {
            com.moriafly.salt.ui.Text(
                text = text,
                style = SaltTheme.textStyles.sub,
                color = SaltTheme.colors.highlight
            )
        }
    } else {
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun CleanupRowCard(
    item: FileNameCleanupItem,
    selected: Boolean,
    onToggle: () -> Unit
) {
    val salt = LocalUiEngine.current == UiEngine.SALT
    val context = LocalContext.current
    val sizeText = Formatter.formatFileSize(context, item.song.fileSize)

    if (salt) {
        com.moriafly.salt.ui.Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center
                ) {
                    com.moriafly.salt.ui.Icon(
                        painter = androidx.compose.ui.graphics.vector.rememberVectorPainter(
                            if (selected) SaltIcons.Check else SaltIcons.Uncheck
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = SaltTheme.colors.highlight
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    com.moriafly.salt.ui.Text(
                        text = item.newStem,
                        style = SaltTheme.textStyles.main,
                        color = SaltTheme.colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    com.moriafly.salt.ui.Text(
                        text = "${item.oldStem} · $sizeText",
                        style = SaltTheme.textStyles.sub,
                        color = SaltTheme.colors.subText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        return
    }

    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                state = if (selected) ToggleableState.On else ToggleableState.Off,
                onClick = onToggle
            )
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    text = item.newStem,
                    style = MiuixTheme.textStyles.main,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.oldStem} · $sizeText",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CleanupFooter(
    state: FileNameCleanupUiState,
    onRename: () -> Unit
) {
    val salt = LocalUiEngine.current == UiEngine.SALT
    val canRename = state.selectedCount > 0 && !state.renaming
    val label = when {
        state.renaming -> stringResource(R.string.batch_edit_processing)
        else -> stringResource(R.string.filename_cleanup_rename_count, state.selectedCount)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (salt) {
            com.moriafly.salt.ui.Button(
                onClick = { if (canRename) onRename() },
                text = label,
                modifier = Modifier.fillMaxWidth(),
                enabled = canRename
            )
        } else {
            Button(
                onClick = { if (canRename) onRename() },
                modifier = Modifier.fillMaxWidth(),
                enabled = canRename,
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(text = label, style = MiuixTheme.textStyles.button)
            }
        }
    }
}
