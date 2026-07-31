package app.monote.mobile.ui.navigation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ShellStartupTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun initializationFailureOffersRetryAndShowsLibraryOnlyAfterSuccess() {
        var attempts = 0
        composeRule.setContent {
            MaterialTheme {
                AuthorizedShell(
                    initializeLibrary = {
                        attempts += 1
                        if (attempts == 1) throw IOException("storage unavailable")
                    },
                    onPermissionLost = {},
                )
            }
        }

        waitForText("资料库初始化失败")
        composeRule.onNodeWithText("资料库初始化失败").assertIsDisplayed()
        composeRule.onNodeWithTag("route-library").assertDoesNotExist()

        composeRule.onNodeWithText("重试").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("资料库").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("route-library").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(2, attempts) }
    }

    @Test
    fun securityFailureRefreshesPermissionWithoutShowingNavigation() {
        val refreshed = AtomicBoolean(false)
        composeRule.setContent {
            MaterialTheme {
                AuthorizedShell(
                    initializeLibrary = { throw SecurityException("permission revoked") },
                    onPermissionLost = { refreshed.set(true) },
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { refreshed.get() }
        composeRule.onNodeWithTag("route-library").assertDoesNotExist()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
