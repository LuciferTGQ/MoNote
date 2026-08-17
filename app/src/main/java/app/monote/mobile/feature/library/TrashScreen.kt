package app.monote.mobile.feature.library

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun TrashScreen(
    entries: List<TrashEntry>,
    onRestore: (TrashEntry) -> Unit,
    onDeletePermanently: (String) -> Unit,
    onEmptyTrash: () -> Unit,
    error: String? = null,
) {
    var pendingDelete by remember { mutableStateOf<TrashEntry?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("回收站", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { confirmEmpty = true }, enabled = entries.isNotEmpty()) { Text("清空回收站") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(entries, key = TrashEntry::stableId) { entry ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.originalRelativePath)
                            Text(entry.deletedAt.toString(), style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onRestore(entry) }) { Text("恢复") }
                        TextButton(
                            onClick = { pendingDelete = entry },
                            modifier = Modifier.semantics { contentDescription = "永久删除 ${entry.originalRelativePath}" },
                        ) { Text("删除") }
                    }
                }
            }
        }
    }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("永久删除后无法恢复") },
            text = { Text(entry.originalRelativePath) },
            confirmButton = { Button(onClick = { pendingDelete = null; onDeletePermanently(entry.stableId) }) { Text("确认永久删除") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("确认清空回收站") },
            text = { Text("清空后无法恢复") },
            confirmButton = { Button(onClick = { confirmEmpty = false; onEmptyTrash() }) { Text("确认清空") } },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("取消") } },
        )
    }
}

fun restoreResultMessage(result: LibraryResult): String? = when (result) {
    is LibraryResult.Success -> if (result.catalogSynchronized) null else "已恢复，但索引同步失败，请重新扫描"
    is LibraryResult.Conflict -> "恢复失败：目标位置已有 ${result.existing.name}"
    is LibraryResult.Failure -> "恢复失败：${result.message}"
}

fun deleteResultMessage(result: TrashDeleteResult, action: String): String? = when (result) {
    is TrashDeleteResult.Success -> null
    TrashDeleteResult.ConfirmationRequired -> "$action 需要再次确认"
    is TrashDeleteResult.NotFound -> "$action 失败：项目不存在"
    is TrashDeleteResult.Failure -> "$action 失败：${result.message}"
}
