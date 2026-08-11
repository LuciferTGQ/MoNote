package app.monote.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import app.monote.mobile.ui.navigation.MoNoteApp
import app.monote.mobile.ui.theme.MoNoteTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MoNoteTheme {
                MoNoteApp(appContainer = (application as MoNoteApplication).container)
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
