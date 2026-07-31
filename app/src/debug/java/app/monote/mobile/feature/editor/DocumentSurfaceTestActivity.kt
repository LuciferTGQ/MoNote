package app.monote.mobile.feature.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.monote.mobile.feature.editor.bridge.DocumentSurfaceController
import java.io.File

class DocumentSurfaceTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configuration = requireNotNull(configured) {
            "DocumentSurfaceTestActivity must be configured before launch"
        }
        setContent {
            DocumentSurface(
                controller = configuration.controller,
                libraryRoot = configuration.libraryRoot,
            )
        }
    }

    companion object {
        @Volatile
        private var configured: Configuration? = null

        fun configure(
            controller: DocumentSurfaceController,
            libraryRoot: File,
        ) {
            configured = Configuration(controller, libraryRoot)
        }

        fun clearConfiguration() {
            configured = null
        }
    }

    private data class Configuration(
        val controller: DocumentSurfaceController,
        val libraryRoot: File,
    )
}
