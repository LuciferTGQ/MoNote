package app.monote.mobile.feature.library

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.monote.mobile.core.storage.SafePathPolicy

@Composable
fun ImportSheet(
    selectedNames: List<String>,
    targetDirectory: String,
    warning: String? = null,
    isFolder: Boolean = false,
    error: String? = null,
    confirming: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val safeNames = selectedNames.map(SafePathPolicy::sanitizeFileName)
    AlertDialog(
        onDismissRequest = { if (!confirming) onDismiss() },
        title = { Text(if (isFolder) "导入文件夹" else "导入 Markdown") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                Text(if (isFolder) "已选择文件夹：${selectedNames.firstOrNull().orEmpty()}" else "已选择 ${selectedNames.size} 个文件")
                Text("安全名称：${safeNames.joinToString("、")}")
                Text("目标：$targetDirectory")
                Text("同名文件将自动加序号")
                warning?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !confirming && selectedNames.isNotEmpty()) {
                Text(if (confirming) "正在导入…" else "导入并打开")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !confirming) { Text("取消") } },
    )
}
