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
import com.lonx.lyrico.utils.DuplicateGroup
import com.lonx.lyrico.utils.DuplicateItem
import com.lonx.lyrico.viewmodel.DuplicateScanUiState
import com.lonx.lyrico.viewmodel.DuplicateScanViewModel
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
 * 查重页：扫描整个曲库，按文件名找出重复分组，勾选后删除多余文件。
 * 每组默认只保留「建议保留」的一个，其余预勾选删除。
 */
@Destination<RootGraph>(route = "duplicate_scan")
@Composable
fun DuplicateScanScreen(
    navigator: DestinationsNavigator
) {
    val viewModel: DuplicateScanViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val salt = LocalUiEngine.current == UiEngine.SALT
    var showConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.scan() }

    if (showConfirm) {
        if (salt) {
            SaltConfirmDialog(
                title = stringResource(R.string.duplicate_scan_delete_title),
                summary = stringResource(R.string.duplicate_scan_delete_count, state.selectedCount),
                onDismissRequest = { showConfirm = false },
                onConfirm = { viewModel.deleteSelected(); showConfirm = false },
                cancelText = stringResource(R.string.cancel),
                confirmText = stringResource(R.string.confirm),
            )
        } else {
            WindowDialog(
                title = stringResource(R.string.duplicate_scan_delete_title),
                show = showConfirm,
                onDismissRequest = { showConfirm = false }
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.duplicate_scan_delete_count, state.selectedCount),
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
                            onClick = { viewModel.deleteSelected(); showConfirm = false },
                            modifier = Modifier.weight(1f),
                            primary = true
                        )
                    }
                }
            }
        }
    }

    LibraryDetailPage(
        title = stringResource(R.string.duplicate_scan_title),
        onBack = { navigator.popBackStack() },
        actions = {
            SaltIconButton(
                icon = LucideIcons.RotateCcw,
                label = stringResource(R.string.duplicate_scan_title),
                enabled = !state.scanning,
                onClick = { viewModel.scan() }
            )
        },
        miuixTopBar = {
            SmallTopAppBar(
                title = stringResource(R.string.duplicate_scan_title),
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
                            contentDescription = stringResource(R.string.duplicate_scan_title)
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
                } else if (state.groups.isEmpty()) {
                    item {
                        IllustratedEmptyState(
                            kind = EmptyStateKind.Tasks,
                            title = stringResource(R.string.duplicate_scan_no_duplicates),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    items(state.groups, key = { it.key }) { group ->
                        DuplicateGroupCard(
                            group = group,
                            selected = state.selectedForDeletion,
                            onToggle = viewModel::toggle
                        )
                    }
                }
            }

            DuplicateFooter(
                state = state,
                onDelete = { showConfirm = true }
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
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    selected: Set<String>,
    onToggle: (String) -> Unit
) {
    val salt = LocalUiEngine.current == UiEngine.SALT
    val keepIndex = group.keepIndex

    if (salt) {
        com.moriafly.salt.ui.Card(modifier = Modifier.padding(horizontal = 12.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                group.files.forEachIndexed { i, item ->
                    DuplicateFileRow(
                        item = item,
                        selected = item.id in selected,
                        keep = i == keepIndex,
                        salt = true,
                        onToggle = { onToggle(item.id) }
                    )
                }
            }
        }
        return
    }

    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        Column {
            group.files.forEachIndexed { i, item ->
                DuplicateFileRow(
                    item = item,
                    selected = item.id in selected,
                    keep = i == keepIndex,
                    salt = false,
                    onToggle = { onToggle(item.id) }
                )
            }
        }
    }
}

@Composable
private fun DuplicateFileRow(
    item: DuplicateItem,
    selected: Boolean,
    keep: Boolean,
    salt: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current
    val sizeText = Formatter.formatFileSize(context, item.size)
    val keepLabel = stringResource(R.string.duplicate_scan_keep)

    if (salt) {
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
                    text = item.stem(),
                    style = SaltTheme.textStyles.main,
                    color = SaltTheme.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                com.moriafly.salt.ui.Text(
                    text = if (keep) "$sizeText · $keepLabel" else sizeText,
                    style = SaltTheme.textStyles.sub,
                    color = if (keep) SaltTheme.colors.highlight else SaltTheme.colors.subText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        return
    }

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
                text = item.stem(),
                style = MiuixTheme.textStyles.main,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (keep) "$sizeText · $keepLabel" else sizeText,
                style = MiuixTheme.textStyles.footnote1,
                color = if (keep) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DuplicateFooter(
    state: DuplicateScanUiState,
    onDelete: () -> Unit
) {
    val salt = LocalUiEngine.current == UiEngine.SALT
    val canDelete = state.selectedCount > 0 && !state.deleting
    val label = when {
        state.deleting -> stringResource(R.string.batch_edit_processing)
        else -> stringResource(R.string.duplicate_scan_delete_count, state.selectedCount)
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        if (salt) {
            com.moriafly.salt.ui.Button(
                onClick = { if (canDelete) onDelete() },
                text = label,
                modifier = Modifier.fillMaxWidth(),
                enabled = canDelete
            )
        } else {
            Button(
                onClick = { if (canDelete) onDelete() },
                modifier = Modifier.fillMaxWidth(),
                enabled = canDelete,
                colors = ButtonDefaults.buttonColorsPrimary()
            ) {
                Text(text = label, style = MiuixTheme.textStyles.button)
            }
        }
    }
}
