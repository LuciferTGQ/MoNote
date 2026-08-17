package app.monote.mobile.feature.editor

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun ExitConfirmationDialog(
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("保存修改？") },
        text = { Text("这份笔记还有未保存的修改。保存后退出，或放弃本次修改。") },
        confirmButton = { TextButton(onClick = onSave) { Text("保存并退出") } },
        dismissButton = {
            TextButton(onClick = onDiscard) { Text("放弃修改") }
            TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}
