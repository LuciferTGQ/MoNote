package app.monote.mobile.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onQueryChange: (String) -> Unit = {},
    onOpenItem: (LibraryItem) -> Unit = {},
    onOpenStorage: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onNavigateUp: () -> Unit = {},
    onImportDocuments: () -> Unit = {},
    onImportFolder: () -> Unit = {},
    onCreateNote: (String) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onSelectionChange: (Set<String>) -> Unit = {},
    onFavorite: (Set<String>) -> Unit = {},
    onTags: (Set<String>, Set<String>) -> Unit = { _, _ -> },
    onMove: (Set<String>, String) -> Unit = { _, _ -> },
    onRename: (String) -> Unit = {},
    onExport: (Set<String>) -> Unit = {},
    onDelete: (Set<String>) -> Unit = {},
    onRecoverMove: (String) -> Unit = {},
) {
    var addExpanded by remember { mutableStateOf(false) }
    var nameDialog by remember { mutableStateOf<NameDialog?>(null) }
    val selecting = state.selectedIds.isNotEmpty()
    val selectedItems = state.items.filter { it.id in state.selectedIds }
    val allDocuments = selectedItems.isNotEmpty() && selectedItems.all { !it.isFolder }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(if (selecting) "已选择 ${state.selectedIds.size} 项" else "墨笺") },
                    navigationIcon = {
                        if (state.canNavigateUp) {
                            TextButton(
                                onClick = onNavigateUp,
                                modifier = Modifier.testTag("library-navigate-up"),
                            ) { Text("上一级") }
                        }
                    },
                    actions = {
                        TextButton(onClick = onOpenStorage) { Text("存储") }
                        TextButton(onClick = onOpenSettings) { Text("设置") }
                    },
                )
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    label = { Text("搜索文件名与正文") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("library-search"),
                )
                if (selecting) {
                    SelectionActions(
                        showMetadata = selectedItems.isNotEmpty(),
                        showRename = selectedItems.size == 1,
                        showExport = selectedItems.size == 1 && allDocuments,
                        onFavorite = { onFavorite(state.selectedIds) },
                        onTags = { nameDialog = NameDialog.Tags },
                        onMove = { nameDialog = NameDialog.Move },
                        onRename = { nameDialog = NameDialog.Rename },
                        onExport = { onExport(state.selectedIds) },
                        onDelete = { onDelete(state.selectedIds) },
                    )
                }
            }
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(
                    onClick = { addExpanded = true },
                    modifier = Modifier.semantics { contentDescription = "添加" },
                ) { Text("＋") }
                DropdownMenu(expanded = addExpanded, onDismissRequest = { addExpanded = false }) {
                    DropdownMenuItem(text = { Text("导入 Markdown") }, onClick = { addExpanded = false; onImportDocuments() })
                    DropdownMenuItem(text = { Text("导入文件夹") }, onClick = { addExpanded = false; onImportFolder() })
                    DropdownMenuItem(text = { Text("新建笔记") }, onClick = { addExpanded = false; nameDialog = NameDialog.Note })
                    DropdownMenuItem(text = { Text("新建目录") }, onClick = { addExpanded = false; nameDialog = NameDialog.Folder })
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.scanning) item { Text("正在扫描资料库…", Modifier.padding(16.dp)) }
            if (state.scanWarnings.isNotEmpty()) {
                item {
                    Column(
                        Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("library-scan-warnings"),
                    ) {
                        Text("部分文件未能扫描", color = MaterialTheme.colorScheme.error)
                        state.scanWarnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) } }
            if (state.moveRecoveryRecords.isNotEmpty()) {
                item {
                    Text(
                        "已记录待恢复",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                    )
                }
                items(state.moveRecoveryRecords, key = { "recovery:${it.id}" }) { recovery ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${recovery.current.path} → ${recovery.original.path}")
                            Text(recovery.message, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onRecoverMove(recovery.id) }) { Text("执行恢复") }
                        }
                    }
                }
            }
            if (state.recent.isNotEmpty()) {
                item { Text("最近", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)) }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.recent.take(3).forEach { recent ->
                            Card(onClick = { onOpenItem(recent) }, modifier = Modifier.weight(1f)) {
                                Text(recent.displayName, Modifier.padding(12.dp), maxLines = 2)
                            }
                        }
                    }
                }
            }
            item { Text("${state.currentFolder.name.ifBlank { "MoNote" }} · 内容", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp)) }
            items(state.items, key = { it.id }) { item ->
                LibraryItemRow(
                    item = item,
                    selected = item.id in state.selectedIds,
                    selectionMode = selecting,
                    onClick = {
                        if (selecting) onSelectionChange(state.selectedIds.toggle(item.id)) else onOpenItem(item)
                    },
                    onLongClick = { onSelectionChange(state.selectedIds + item.id) },
                )
            }
        }
    }

    nameDialog?.let { kind ->
        NameInputDialog(
            title = when (kind) { NameDialog.Note -> "新建笔记"; NameDialog.Folder -> "新建目录"; NameDialog.Rename -> "重命名"; NameDialog.Tags -> "设置标签"; NameDialog.Move -> "移动到目录" },
            onDismiss = { nameDialog = null },
            onConfirm = { value ->
                when (kind) {
                    NameDialog.Note -> onCreateNote(value)
                    NameDialog.Folder -> onCreateFolder(value)
                    NameDialog.Rename -> onRename(value)
                    NameDialog.Tags -> onTags(state.selectedIds, value.split(',').map(String::trim).filter(String::isNotEmpty).toSet())
                    NameDialog.Move -> onMove(state.selectedIds, value)
                }
                nameDialog = null
            },
        )
    }
}

@Composable
private fun SelectionActions(
    showMetadata: Boolean,
    showRename: Boolean,
    showExport: Boolean,
    onFavorite: () -> Unit,
    onTags: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                buildList {
                    if (showMetadata) {
                        add("收藏" to onFavorite)
                        add("标签" to onTags)
                    }
                    add("移动" to onMove)
                    if (showRename) add("重命名" to onRename)
                    if (showExport) add("导出" to onExport)
                    add("删除" to onDelete)
                }
                    .forEach { (label, action) -> TextButton(onClick = action) { Text(label) } }
            }
        }
    }
}

@Composable
private fun NameInputDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("名称") }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text("确认") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id
private enum class NameDialog { Note, Folder, Rename, Tags, Move }
