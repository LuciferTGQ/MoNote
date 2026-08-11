package app.monote.mobile.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class ClearableStorageCategory { RecoveryDrafts, RendererCache }

@Composable
fun StorageScreen(
    breakdown: StorageBreakdown?,
    onOpenRoot: () -> Unit,
    onClear: (ClearableStorageCategory) -> Unit,
    error: String? = null,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("存储管理")
        Button(onClick = onOpenRoot) { Text("打开 MoNote 公共根目录") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (breakdown == null) {
            Text("正在统计…")
        } else {
            StorageRow("正文", breakdown.documentsBytes)
            StorageRow("附件", breakdown.attachmentsBytes)
            StorageRow("回收站", breakdown.trashBytes)
            StorageRow("恢复草稿", breakdown.recoveryBytes) {
                onClear(ClearableStorageCategory.RecoveryDrafts)
            }
            StorageRow("备份", breakdown.backupsBytes)
            StorageRow("渲染缓存", breakdown.cacheBytes) {
                onClear(ClearableStorageCategory.RendererCache)
            }
            breakdown.warnings.forEach { Text(it) }
        }
    }
}

@Composable
private fun StorageRow(label: String, bytes: Long, onClear: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Row {
            Text(formatBytes(bytes), Modifier.padding(vertical = 12.dp))
            if (onClear != null) TextButton(onClick = onClear) { Text("清理") }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
