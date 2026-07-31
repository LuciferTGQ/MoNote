package app.monote.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.monote.mobile.ui.navigation.MoNoteApp
import app.monote.mobile.ui.theme.MoNoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MoNoteTheme {
                MoNoteApp(appContainer = (application as MoNoteApplication).container)
            }
        }
    }
}
