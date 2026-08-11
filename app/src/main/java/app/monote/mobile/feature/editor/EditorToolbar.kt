package app.monote.mobile.feature.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.monote.mobile.feature.editor.bridge.EditorCommand

@Composable
fun HistoryActions(
    canUndo: Boolean,
    canRedo: Boolean,
    onCommand: (EditorCommand) -> Unit,
) {
    IconButton(
        enabled = canUndo,
        onClick = { onCommand(EditorCommand.UNDO) },
        modifier = Modifier.semantics { contentDescription = "撤回" },
    ) { Text("↶", style = MaterialTheme.typography.headlineSmall) }
    IconButton(
        enabled = canRedo,
        onClick = { onCommand(EditorCommand.REDO) },
        modifier = Modifier.semantics { contentDescription = "重做" },
    ) { Text("↷", style = MaterialTheme.typography.headlineSmall) }
}

@Composable
fun EditorToolbar(onCommand: (EditorCommand) -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TOOLBAR_ACTIONS.forEach { action ->
            AssistChip(
                onClick = { onCommand(action.command) },
                label = { Text(action.label) },
                modifier = Modifier.semantics { contentDescription = action.description },
            )
        }
    }
}

private data class ToolbarAction(
    val label: String,
    val description: String,
    val command: EditorCommand,
)

private val TOOLBAR_ACTIONS = listOf(
    ToolbarAction("H", "标题", EditorCommand.HEADING),
    ToolbarAction("B", "粗体", EditorCommand.BOLD),
    ToolbarAction("I", "斜体", EditorCommand.ITALIC),
    ToolbarAction("S", "删除线", EditorCommand.STRIKE),
    ToolbarAction("•", "无序列表", EditorCommand.BULLET_LIST),
    ToolbarAction("☐", "任务列表", EditorCommand.TASK_LIST),
    ToolbarAction("❯", "引用", EditorCommand.QUOTE),
    ToolbarAction("链接", "插入链接", EditorCommand.LINK),
    ToolbarAction("图片", "插入图片", EditorCommand.IMAGE),
    ToolbarAction("代码", "行内代码", EditorCommand.CODE),
    ToolbarAction("表格", "插入表格", EditorCommand.TABLE),
    ToolbarAction("公式", "插入数学公式", EditorCommand.MATH),
    ToolbarAction("图", "插入 Mermaid 图", EditorCommand.MERMAID),
)
