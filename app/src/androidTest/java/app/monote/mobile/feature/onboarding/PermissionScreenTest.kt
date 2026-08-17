package app.monote.mobile.feature.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PermissionScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deniedPermissionExplainsAccessAndRequestsSystemSettings() {
        var requested = false
        composeRule.setContent {
            MaterialTheme {
                PermissionScreen(
                    granted = false,
                    onRequest = { requested = true },
                )
            }
        }

        composeRule.onNodeWithText("允许访问和管理文件").assertIsDisplayed()
        composeRule.onNodeWithText("前往系统设置")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertTrue(requested) }
    }

    @Test
    fun grantedPermissionExposesReadyStateWithoutDeniedContent() {
        composeRule.setContent {
            MaterialTheme {
                PermissionScreen(
                    granted = true,
                    onRequest = {},
                )
            }
        }

        composeRule.onNodeWithText("允许访问和管理文件").assertDoesNotExist()
        composeRule.onNodeWithText("前往系统设置").assertDoesNotExist()
        composeRule.onNodeWithTag("permission-granted")
            .assertTextContains("存储权限已授予")
    }
}
