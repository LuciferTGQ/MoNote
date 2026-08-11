package app.monote.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.monote.mobile.feature.settings.AppSettingsStore
import app.monote.mobile.feature.settings.AppThemePreference
import app.monote.mobile.ui.navigation.MoNoteApp
import app.monote.mobile.ui.theme.MoNoteTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val container = (application as MoNoteApplication).container
            val settingsStore = remember { AppSettingsStore(container.settings) }
            val theme by settingsStore.theme.collectAsStateWithLifecycle(
                initialValue = AppThemePreference.FollowSystem,
            )
            MoNoteTheme(theme) {
                MoNoteApp(appContainer = container)
            }
        }
        dispatchIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchIncoming(intent)
    }

    private fun dispatchIncoming(intent: Intent) {
        if (intent.action !in INCOMING_ACTIONS) return
        lifecycleScope.launch {
            (application as MoNoteApplication).container.receiveIncomingIntent(intent)
        }
    }

    private companion object {
        val INCOMING_ACTIONS = setOf(
            Intent.ACTION_VIEW,
            Intent.ACTION_EDIT,
            Intent.ACTION_SEND,
            Intent.ACTION_SEND_MULTIPLE,
        )
    }
}
