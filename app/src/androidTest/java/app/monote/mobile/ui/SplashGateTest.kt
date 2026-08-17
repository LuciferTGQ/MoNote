package app.monote.mobile.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test

class SplashGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visibleSplashDoesNotComposeUnderlyingContent() {
        var finishSplash: (() -> Unit)? = null
        composeRule.setContent {
            SplashGate(
                initiallyVisible = true,
                splash = { onFinished ->
                    finishSplash = onFinished
                    Text(
                        text = "墨笺 · MoNote",
                        modifier = Modifier.testTag("splash-brand"),
                    )
                },
            ) {
                Text(
                    text = "权限或导航内容",
                    modifier = Modifier.testTag("shell-content"),
                )
            }
        }

        composeRule.onNodeWithTag("splash-brand").assertIsDisplayed()
        composeRule.onNodeWithTag("shell-content").assertDoesNotExist()

        composeRule.runOnIdle { checkNotNull(finishSplash).invoke() }

        composeRule.onNodeWithTag("splash-brand").assertDoesNotExist()
        composeRule.onNodeWithTag("shell-content").assertIsDisplayed()
    }
}
