package app.monote.mobile.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.monote.mobile.feature.editor.bridge.EditorCommand

@Composable
fun DocumentScreen(
    state: EditorUiState,
    isLandscape: Boolean,
    onBack: () -> Unit = {},
    onSave: () -> Unit = {},
    onSelectTab: (EditorTab) -> Unit = {},
    onCommand: (EditorCommand) -> Unit = {},
    onOrientation: (OrientationPreference) -> Unit = {},
    onSplitRatio: (Float) -> Unit = {},
    onSaveAndExit: () -> Unit = {},
    onDiscardAndExit: () -> Unit = {},
    onCancelExit: () -> Unit = {},
    onRestoreRecovery: () -> Unit = {},
    onDismissRecovery: () -> Unit = {},
    onKeepMine: () -> Unit = {},
    onLoadExternal: () -> Unit = {},
    onSaveCopy: () -> Unit = {},
    surface: @Composable (Modifier) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().testTag("document-screen")) {
        EditorTopBar(
            state = state,
            isLandscape = isLandscape,
            onBack = onBack,
            onSave = onSave,
            onCommand = onCommand,
            onOrientation = onOrientation,
        )
        if (!isLandscape) {
            PortraitTabs(state.selectedTab, onSelectTab)
        } else {
            Text(
                text = "左侧编辑 · 右侧预览",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        HorizontalDivider()
        when {
            state.loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.session == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(state.error ?: "无法打开文档")
            }
            else -> EditorSurfaceArea(state, isLandscape, onSplitRatio, surface)
        }
        state.renderWarning?.let {
            Text(
                text = "预览提示：$it",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        state.error?.takeIf { state.session != null }?.let {
            Text(
                text = it,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        state.session?.recoveryError?.let {
            Text(
                text = "草稿备份失败：$it",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (state.session != null && (!isLandscape || state.selectedTab == EditorTab.Edit)) {
            HorizontalDivider()
            EditorToolbar(onCommand)
        }
    }

    if (state.pendingExit) {
        ExitConfirmationDialog(onSaveAndExit, onDiscardAndExit, onCancelExit)
    }
    state.recoveryCandidate?.let {
        AlertDialog(
            onDismissRequest = onDismissRecovery,
            title = { Text("发现上次草稿") },
            text = { Text("上次可能意外退出。要恢复当时未完成的内容吗？") },
            confirmButton = { TextButton(onClick = onRestoreRecovery) { Text("恢复草稿") } },
            dismissButton = { TextButton(onClick = onDismissRecovery) { Text("使用已保存版本") } },
        )
    }
    if (state.session?.externalConflict != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("文件已在外部修改") },
            text = { Text("为避免覆盖其他软件的修改，请选择如何处理。") },
            confirmButton = { TextButton(onClick = onKeepMine) { Text("保留我的版本") } },
            dismissButton = {
                TextButton(onClick = onLoadExternal) { Text("载入外部版本") }
                TextButton(onClick = onSaveCopy) { Text("另存副本") }
            },
        )
    }
}

@Composable
private fun EditorTopBar(
    state: EditorUiState,
    isLandscape: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onCommand: (EditorCommand) -> Unit,
    onOrientation: (OrientationPreference) -> Unit,
) {
    var orientationMenu by remember { mutableStateOf(false) }
    val session = state.session
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics { contentDescription = "返回" },
            ) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
            Column(Modifier.weight(1f)) {
                Text(session?.file?.name ?: "正在打开…", maxLines = 1)
                Text(
                    text = saveStatusText(session),
                    color = if (session?.saveStatus in setOf(SaveStatus.SaveFailed, SaveStatus.Conflict)) {
                        MaterialTheme.colorScheme.error
                    } else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            HistoryActions(session?.canUndo == true, session?.canRedo == true, onCommand)
            IconButton(
                enabled = session?.isDirty == true,
                onClick = onSave,
                modifier = Modifier.semantics { contentDescription = "保存" },
            ) { Text("✓") }
            Box {
                IconButton(
                    onClick = { orientationMenu = true },
                    modifier = Modifier.semantics { contentDescription = "屏幕方向" },
                ) { Text(if (isLandscape) "▭" else "▯") }
                DropdownMenu(
                    expanded = orientationMenu,
                    onDismissRequest = { orientationMenu = false },
                ) {
                    OrientationPreference.entries.forEach { preference ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    (if (preference == state.orientationPreference) "✓ " else "") +
                                        orientationLabel(preference),
                                )
                            },
                            onClick = {
                                orientationMenu = false
                                onOrientation(preference)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PortraitTabs(selected: EditorTab, onSelect: (EditorTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        EditorTab.entries.forEach { tab ->
            val label = if (tab == EditorTab.Edit) "编辑" else "预览"
            if (tab == selected) Button(onClick = { onSelect(tab) }) { Text(label) }
            else TextButton(onClick = { onSelect(tab) }) { Text(label) }
        }
    }
}

@Composable
private fun ColumnScope.EditorSurfaceArea(
    state: EditorUiState,
    isLandscape: Boolean,
    onSplitRatio: (Float) -> Unit,
    surface: @Composable (Modifier) -> Unit,
) {
    val modeTag = if (isLandscape) "editor-mode-split"
    else if (state.selectedTab == EditorTab.Edit) "editor-mode-edit" else "editor-mode-preview"
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().weight(1f).testTag(modeTag),
    ) {
        surface(Modifier.fillMaxSize())
        if (isLandscape) {
            val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val handleHalfPx = with(LocalDensity.current) { 10.dp.toPx() }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(20.dp)
                    .align(Alignment.CenterStart)
                    .graphicsLayer { translationX = widthPx * state.splitRatio - handleHalfPx }
                    .padding(horizontal = 8.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .testTag("split-divider")
                    .pointerInput(widthPx, state.splitRatio) {
                        var ratio = state.splitRatio
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            ratio = (ratio + dragAmount / widthPx).coerceIn(0.25f, 0.75f)
                            onSplitRatio(ratio)
                        }
                    },
            )
        }
    }
}

private fun saveStatusText(session: DocumentSession?): String = when (session?.saveStatus) {
    null -> ""
    SaveStatus.Saved -> "已保存"
    SaveStatus.Unsaved -> "未保存"
    SaveStatus.Saving -> "正在保存…"
    SaveStatus.ReadOnly -> "只读"
    SaveStatus.SaveFailed -> "保存失败"
    SaveStatus.Conflict -> "外部修改冲突"
}

private fun orientationLabel(preference: OrientationPreference): String = when (preference) {
    OrientationPreference.FollowSystem -> "跟随系统旋转"
    OrientationPreference.Portrait -> "锁定竖屏"
    OrientationPreference.Landscape -> "锁定横屏"
}
