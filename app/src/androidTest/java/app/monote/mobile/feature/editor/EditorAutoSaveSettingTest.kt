package app.monote.mobile.feature.editor

import android.os.Environment
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.monote.mobile.core.storage.LibraryPaths
import app.monote.mobile.feature.settings.AppSettingsStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorAutoSaveSettingTest {
    @Test
    fun disabledPreferenceIsAppliedBeforeTheDocumentSessionLoads() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val testId = UUID.randomUUID().toString()
        val settingsFile = File(context.cacheDir, "editor-settings-$testId.preferences_pb")
        val libraryRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "MoNoteEditorTest-$testId",
        )
        val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.create(scope = settingsScope) { settingsFile }
        val appSettings = AppSettingsStore(dataStore)
        val paths = LibraryPaths(libraryRoot).ensureCreated()
        val documentFile = paths.root.resolve("note.md").apply { writeText("initial") }
        val viewModelStore = ViewModelStore()

        try {
            appSettings.setAutoSave(false)
            val model = EditorViewModel(
                document = editorDocumentFor(documentFile, paths.root),
                paths = paths,
                recoveryStore = RecoveryStore(paths.recovery),
                orientationStore = OrientationPreferenceStore(dataStore),
                appSettingsStore = appSettings,
            )
            viewModelStore.put("editor", model)

            val state = withTimeout(5_000) {
                model.uiState.first { it.session != null || !it.loading }
            }
            val session = state.session
            assertNotNull(state.error ?: "document session failed to load", session)

            assertFalse(session!!.autoSaveEnabled)
            assertFalse(session.autoSaveAllowed)
        } finally {
            viewModelStore.clear()
            settingsScope.cancel()
            settingsFile.delete()
            libraryRoot.deleteRecursively()
        }
    }
}
