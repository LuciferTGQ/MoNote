package app.monote.mobile.feature.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.monote.mobile.feature.editor.OrientationPreference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsRealPreferencesAndDispatchesChanges() {
        var selectedTheme: AppThemePreference? = null
        var selectedFont: EditorFontSize? = null
        var autoSave: Boolean? = null
        var orientation: OrientationPreference? = null
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(),
                    onTheme = { selectedTheme = it },
                    onFontSize = { selectedFont = it },
                    onAutoSave = { autoSave = it },
                    onOrientation = { orientation = it },
                )
            }
        }

        listOf("设置", "主题", "字号", "自动保存", "默认方向").forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        composeRule.onNodeWithText("深色").performClick()
        composeRule.onNodeWithText("大").performClick()
        composeRule.onNodeWithContentDescription("自动保存").performClick()
        composeRule.onNodeWithText("锁定横屏").performScrollTo().performClick()

        assertEquals(AppThemePreference.Dark, selectedTheme)
        assertEquals(EditorFontSize.Large, selectedFont)
        assertEquals(false, autoSave)
        assertEquals(OrientationPreference.Landscape, orientation)
    }
}
