package app.monote.mobile.feature.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsStoreTest {
    @Test
    fun preferencesPersistAndAreReadByANewStoreInstance() = runTest {
        val themeData = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            settingsFile("theme")
        }
        AppSettingsStore(themeData).setTheme(AppThemePreference.Dark)
        assertEquals(AppThemePreference.Dark, AppSettingsStore(themeData).theme.first())

        val fontData = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            settingsFile("font")
        }
        AppSettingsStore(fontData).setFontSize(EditorFontSize.Large)
        assertEquals(EditorFontSize.Large, AppSettingsStore(fontData).fontSize.first())

        val autoSaveData = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            settingsFile("autosave")
        }
        AppSettingsStore(autoSaveData).setAutoSave(false)
        assertFalse(AppSettingsStore(autoSaveData).autoSave.first())
    }

    @Test
    fun defaultsAreSystemThemeStandardTextAndAutoSave() = runTest {
        val file = Files.createTempDirectory("monote-settings-defaults")
            .resolve("settings.preferences_pb")
            .toFile()
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val settings = AppSettingsStore(dataStore)

        assertEquals(AppThemePreference.FollowSystem, settings.theme.first())
        assertEquals(EditorFontSize.Standard, settings.fontSize.first())
        assertEquals(true, settings.autoSave.first())
    }

    private fun settingsFile(name: String) = Files.createTempDirectory("monote-settings-$name")
        .resolve("settings.preferences_pb")
        .toFile()
}
