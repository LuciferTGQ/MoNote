package app.monote.mobile.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryItemRow(
    item: LibraryItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(item.displayName) },
        supportingContent = {
            Column {
                if (item.tags.isNotEmpty()) Text(item.tags.sorted().joinToString(" ") { "#$it" })
                if (item.favorite) Text("★ 收藏")
            }
        },
        leadingContent = { Text(if (item.isFolder) "📁" else "MD") },
        trailingContent = {
            if (selectionMode) Checkbox(checked = selected, onCheckedChange = { onClick() })
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 4.dp),
    )
}
