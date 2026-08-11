package app.monote.mobile.feature.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import app.monote.mobile.core.storage.DocumentFingerprint
import app.monote.mobile.feature.editor.bridge.DocumentSurfaceController
import java.io.File

class DocumentSurfaceTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configuration = requireNotNull(configured) {
            "DocumentSurfaceTestActivity must be configured before launch"
        }
        setContent {
            MaterialTheme {
                if (configuration.embedded) {
                    val file = configuration.libraryRoot.resolve("embedded.md")
                    DocumentScreen(
                        state = EditorUiState(
                            session = DocumentSession(
                                id = "embedded-document",
                                file = file,
                                text = configuration.text,
                                revision = 0,
                                baseline = DocumentFingerprint(
                                    configuration.text.toByteArray().size.toLong(),
                                    1,
                                    DocumentFingerprint.sha256(configuration.text),
                                ),
                                saveStatus = SaveStatus.Saved,
                                canUndo = false,
                                canRedo = false,
                                autoSaveEnabled = true,
                            ),
                            loading = false,
                        ),
                        isLandscape = false,
                        surface = { modifier ->
                            DocumentSurface(
                                controller = configuration.controller,
                                libraryRoot = configuration.libraryRoot,
                                modifier = modifier,
                            )
                        },
                    )
                } else {
                    DocumentSurface(
                        controller = configuration.controller,
                        libraryRoot = configuration.libraryRoot,
                    )
                }
            }
        }
    }

    companion object {
        @Volatile
        private var configured: Configuration? = null

        fun configure(
            controller: DocumentSurfaceController,
            libraryRoot: File,
            embedded: Boolean = false,
            text: String = "",
        ) {
            configured = Configuration(controller, libraryRoot, embedded, text)
        }

        fun clearConfiguration() {
            configured = null
        }
    }

    private data class Configuration(
        val controller: DocumentSurfaceController,
        val libraryRoot: File,
        val embedded: Boolean,
        val text: String,
    )
}
