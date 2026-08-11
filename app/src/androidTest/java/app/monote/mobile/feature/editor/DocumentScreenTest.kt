package app.monote.mobile.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.monote.mobile.core.storage.DocumentFingerprint
import java.io.File
import org.junit.Rule
import org.junit.Test

class DocumentScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun portraitShowsEditPreviewTabsAndHistoryState() {
        show(EditorUiState(session = session(), loading = false), isLandscape = false)

        composeRule.onNodeWithText("编辑").assertIsDisplayed()
        composeRule.onNodeWithText("预览").assertIsDisplayed()
        composeRule.onNodeWithTag("editor-mode-edit").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("撤回").assertIsEnabled()
        composeRule.onNodeWithContentDescription("重做").assertIsNotEnabled()
    }

    @Test
    fun landscapeUsesOneSplitSurfaceAndADraggableDivider() {
        show(EditorUiState(session = session(), loading = false), isLandscape = true)

        composeRule.onNodeWithText("左侧编辑 · 右侧预览").assertIsDisplayed()
        composeRule.onNodeWithTag("editor-mode-split").assertIsDisplayed()
        composeRule.onNodeWithTag("split-divider").assertIsDisplayed()
    }

    @Test
    fun dirtyExitOffersSaveDiscardAndCancel() {
        show(EditorUiState(session = session(), loading = false, pendingExit = true), false)

        composeRule.onNodeWithText("保存修改？").assertIsDisplayed()
        composeRule.onNodeWithText("保存并退出").assertIsDisplayed()
        composeRule.onNodeWithText("放弃修改").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
    }

    private fun show(state: EditorUiState, isLandscape: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                DocumentScreen(
                    state = state,
                    isLandscape = isLandscape,
                    surface = { modifier -> Box(modifier) },
                )
            }
        }
    }

    private fun session() = DocumentSession(
        id = "document-1",
        file = File("biology.md"),
        text = "changed",
        revision = 1,
        baseline = DocumentFingerprint(
            size = 8,
            modifiedAt = 1,
            sha256 = DocumentFingerprint.sha256("original"),
        ),
        saveStatus = SaveStatus.Unsaved,
        canUndo = true,
        canRedo = false,
        autoSaveEnabled = true,
    )
}
