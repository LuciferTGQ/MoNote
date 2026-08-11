package app.monote.mobile.feature.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
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
    var moreExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        COMMON_ACTIONS.forEach { action ->
            TextButton(
                onClick = { onCommand(action.command) },
                modifier = Modifier.semantics { contentDescription = action.description },
            ) { Text(action.label) }
        }
        Box {
            TextButton(onClick = { moreExpanded = true }) { Text("更多") }
            DropdownMenu(
                expanded = moreExpanded,
                onDismissRequest = { moreExpanded = false },
            ) {
                ADVANCED_ACTIONS.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        onClick = {
                            moreExpanded = false
                            onCommand(action.command)
                        },
                        modifier = Modifier.semantics {
                            contentDescription = action.description
                        },
                    )
                }
            }
        }
    }
}

private data class ToolbarAction(
    val label: String,
    val description: String,
    val command: EditorCommand,
)

private val COMMON_ACTIONS = listOf(
    ToolbarAction("标题", "标题", EditorCommand.HEADING),
    ToolbarAction("粗体", "粗体", EditorCommand.BOLD),
    ToolbarAction("列表", "无序列表", EditorCommand.BULLET_LIST),
    ToolbarAction("待办", "任务列表", EditorCommand.TASK_LIST),
)

private val ADVANCED_ACTIONS = listOf(
    ToolbarAction("斜体", "斜体", EditorCommand.ITALIC),
    ToolbarAction("删除线", "删除线", EditorCommand.STRIKE),
    ToolbarAction("引用", "引用", EditorCommand.QUOTE),
    ToolbarAction("链接", "插入链接", EditorCommand.LINK),
    ToolbarAction("图片", "插入图片", EditorCommand.IMAGE),
    ToolbarAction("代码", "行内代码", EditorCommand.CODE),
    ToolbarAction("表格", "插入表格", EditorCommand.TABLE),
    ToolbarAction("公式", "插入数学公式", EditorCommand.MATH),
    ToolbarAction("图", "插入 Mermaid 图", EditorCommand.MERMAID),
)
