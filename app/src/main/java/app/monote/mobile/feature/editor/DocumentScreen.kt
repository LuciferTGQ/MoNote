package app.monote.mobile.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.monote.mobile.feature.editor.bridge.EditorCommand

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    onShowSearch: () -> Unit = {},
    onHideSearch: () -> Unit = {},
    onSearchQuery: (String) -> Unit = {},
    onFindPrevious: () -> Unit = {},
    onFindNext: () -> Unit = {},
    onShowOutline: (OutlineTab) -> Unit = {},
    onDismissOutline: () -> Unit = {},
    onSelectOutlineTab: (OutlineTab) -> Unit = {},
    onNavigateToHeading: (String) -> Unit = {},
    onToggleBookmark: (String) -> Unit = {},
    onEnterReading: () -> Unit = {},
    onExitReading: () -> Unit = {},
    surface: @Composable (Modifier) -> Unit = {},
) {
    Box(Modifier.fillMaxSize().testTag("document-screen")) {
      Column(Modifier.fillMaxSize()) {
        if (!state.readingMode) {
          EditorTopBar(
            state = state,
            isLandscape = isLandscape,
            onBack = onBack,
            onSave = onSave,
            onCommand = onCommand,
            onOrientation = onOrientation,
            onShowSearch = onShowSearch,
            onShowOutline = { onShowOutline(OutlineTab.Outline) },
            onEnterReading = onEnterReading,
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
        }
        if (state.searchVisible) {
            DocumentSearchBar(
                query = state.searchQuery,
                current = state.searchCurrent,
                total = state.searchTotal,
                onQueryChange = onSearchQuery,
                onPrevious = onFindPrevious,
                onNext = onFindNext,
                onClose = onHideSearch,
            )
        }
        when {
            state.loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.session == null -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(state.error ?: "无法打开文档")
            }
            else -> EditorSurfaceArea(state, isLandscape, onSplitRatio, surface)
        }
        if (!state.readingMode) {
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
        }
        if (!state.readingMode && state.session != null && (!isLandscape || state.selectedTab == EditorTab.Edit)) {
            HorizontalDivider()
            EditorToolbar(onCommand)
        }
      }
      if (state.readingMode && state.readingControlsVisible) {
          ReadingControls(
              activeHeadingId = state.activeHeadingId,
              activeBookmarked = state.bookmarks.any {
                  it.available && it.bookmark.id == state.activeHeadingId
              },
              onBack = onBack,
              onSearch = onShowSearch,
              onOutline = { onShowOutline(OutlineTab.Outline) },
              onBookmark = { state.activeHeadingId?.let(onToggleBookmark) },
              onExit = onExitReading,
              modifier = Modifier.align(Alignment.BottomCenter),
          )
      }
    }

    if (state.outlineVisible) {
        OutlineSheet(
            state = state,
            onDismiss = onDismissOutline,
            onSelectTab = onSelectOutlineTab,
            onNavigate = onNavigateToHeading,
            onToggleBookmark = onToggleBookmark,
        )
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
    onShowSearch: () -> Unit,
    onShowOutline: () -> Unit,
    onEnterReading: () -> Unit,
) {
    var orientationMenu by remember { mutableStateOf(false) }
    var readingToolsMenu by remember { mutableStateOf(false) }
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
            Box {
                IconButton(
                    onClick = { readingToolsMenu = true },
                    modifier = Modifier.testTag("reading-tools-action").semantics {
                        contentDescription = "阅读工具"
                    },
                ) { Text("阅") }
                DropdownMenu(
                    expanded = readingToolsMenu,
                    onDismissRequest = { readingToolsMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("文内搜索") },
                        onClick = {
                            readingToolsMenu = false
                            onShowSearch()
                        },
                        modifier = Modifier.testTag("search-action"),
                    )
                    DropdownMenuItem(
                        text = { Text("标题目录") },
                        onClick = {
                            readingToolsMenu = false
                            onShowOutline()
                        },
                        modifier = Modifier.testTag("outline-action"),
                    )
                    DropdownMenuItem(
                        text = { Text("沉浸阅读") },
                        onClick = {
                            readingToolsMenu = false
                            onEnterReading()
                        },
                        modifier = Modifier.testTag("read-action"),
                    )
                }
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
private fun DocumentSearchBar(
    query: String,
    current: Int,
    total: Int,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(tonalElevation = 1.dp, modifier = Modifier.testTag("document-search")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { onQueryChange(it.take(256)) },
                modifier = Modifier.weight(1f).testTag("search-field"),
                singleLine = true,
                label = { Text("搜索正文") },
            )
            Text("$current/$total", style = MaterialTheme.typography.labelMedium)
            IconButton(
                onClick = onPrevious,
                enabled = total > 0,
                modifier = Modifier.semantics { contentDescription = "上一个匹配" },
            ) { Text("↑") }
            IconButton(
                onClick = onNext,
                enabled = total > 0,
                modifier = Modifier.semantics { contentDescription = "下一个匹配" },
            ) { Text("↓") }
            IconButton(onClick = onClose) { Text("×") }
        }
    }
}

@Composable
private fun ReadingControls(
    activeHeadingId: String?,
    activeBookmarked: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOutline: () -> Unit,
    onBookmark: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("reading-controls"),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            TextButton(onClick = onSearch) { Text("搜索") }
            TextButton(onClick = onOutline) { Text("目录") }
            TextButton(onClick = onBookmark, enabled = activeHeadingId != null) {
                Text(if (activeBookmarked) "取消书签" else "书签")
            }
            TextButton(onClick = onExit) { Text("退出阅读") }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OutlineSheet(
    state: EditorUiState,
    onDismiss: () -> Unit,
    onSelectTab: (OutlineTab) -> Unit,
    onNavigate: (String) -> Unit,
    onToggleBookmark: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlineTab.entries.forEach { tab ->
                val label = if (tab == OutlineTab.Outline) "目录" else "书签"
                if (state.outlineTab == tab) {
                    Button(onClick = { onSelectTab(tab) }) { Text(label) }
                } else {
                    TextButton(onClick = { onSelectTab(tab) }) { Text(label) }
                }
            }
        }
        val empty = if (state.outlineTab == OutlineTab.Outline) {
            state.outline.isEmpty()
        } else {
            state.bookmarks.isEmpty()
        }
        if (empty) {
            Box(
                modifier = Modifier.fillMaxWidth().height(160.dp).testTag("outline-empty"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.outlineTab == OutlineTab.Outline) "这份文档还没有标题" else "还没有标题书签",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                if (state.outlineTab == OutlineTab.Outline) {
                    items(state.outline, key = DocumentHeading::id) { heading ->
                        val bookmarked = state.bookmarks.any {
                            it.available && it.bookmark.id == heading.id
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigate(heading.id) }
                                .padding(
                                    start = (16 + (heading.level - 1) * 14).dp,
                                    end = 8.dp,
                                    top = 8.dp,
                                    bottom = 8.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                heading.title,
                                modifier = Modifier.weight(1f),
                                color = if (heading.id == state.activeHeadingId) {
                                    MaterialTheme.colorScheme.primary
                                } else MaterialTheme.colorScheme.onSurface,
                            )
                            TextButton(onClick = { onToggleBookmark(heading.id) }) {
                                Text(if (bookmarked) "已收藏" else "加书签")
                            }
                        }
                    }
                } else {
                    items(state.bookmarks, key = { it.bookmark.id }) { resolved ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = resolved.available) {
                                    onNavigate(resolved.bookmark.id)
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(resolved.bookmark.title)
                                if (!resolved.available) {
                                    Text(
                                        "原位置已失效",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                            TextButton(onClick = { onToggleBookmark(resolved.bookmark.id) }) {
                                Text("移除")
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
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
    val split = isLandscape && !state.readingMode
    val modeTag = if (state.readingMode) "editor-mode-read"
    else if (split) "editor-mode-split"
    else if (state.selectedTab == EditorTab.Edit) "editor-mode-edit" else "editor-mode-preview"
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .clipToBounds()
            .testTag(modeTag),
    ) {
        surface(Modifier.fillMaxSize())
        if (split) {
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
