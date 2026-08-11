package app.monote.mobile.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.monote.mobile.feature.editor.OrientationPreference

data class SettingsUiState(
    val theme: AppThemePreference = AppThemePreference.FollowSystem,
    val fontSize: EditorFontSize = EditorFontSize.Standard,
    val autoSave: Boolean = true,
    val orientation: OrientationPreference = OrientationPreference.FollowSystem,
    val error: String? = null,
)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onTheme: (AppThemePreference) -> Unit,
    onFontSize: (EditorFontSize) -> Unit,
    onAutoSave: (Boolean) -> Unit,
    onOrientation: (OrientationPreference) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        PreferenceGroup(
            title = "主题",
            options = listOf(
                AppThemePreference.FollowSystem to "系统",
                AppThemePreference.Light to "浅色",
                AppThemePreference.Dark to "深色",
            ),
            selected = state.theme,
            onSelect = onTheme,
        )
        HorizontalDivider()
        PreferenceGroup(
            title = "字号",
            options = listOf(
                EditorFontSize.Small to "小",
                EditorFontSize.Standard to "标准",
                EditorFontSize.Large to "大",
            ),
            selected = state.fontSize,
            onSelect = onFontSize,
        )
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onAutoSave(!state.autoSave) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("自动保存", style = MaterialTheme.typography.titleMedium)
                Text(
                    "关闭后仍会保留意外退出恢复草稿",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = state.autoSave,
                onCheckedChange = onAutoSave,
                modifier = Modifier.semantics { contentDescription = "自动保存" },
            )
        }
        HorizontalDivider()
        PreferenceGroup(
            title = "默认方向",
            options = listOf(
                OrientationPreference.FollowSystem to "跟随系统",
                OrientationPreference.Portrait to "锁定竖屏",
                OrientationPreference.Landscape to "锁定横屏",
            ),
            selected = state.orientation,
            onSelect = onOrientation,
        )
        state.error?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun <T> PreferenceGroup(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    options.forEach { (value, label) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(value) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected == value, onClick = { onSelect(value) })
            Text(label, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
