package app.monote.mobile.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class AppThemePreference {
    FollowSystem,
    Light,
    Dark,
}

enum class EditorFontSize(val pixels: Int) {
    Small(14),
    Standard(16),
    Large(20),
}

class AppSettingsStore(private val dataStore: DataStore<Preferences>) {
    private val preferences = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val theme: Flow<AppThemePreference> = preferences.map { stored ->
        stored[THEME_KEY]?.let { value ->
            AppThemePreference.entries.firstOrNull { it.name == value }
        } ?: AppThemePreference.FollowSystem
    }

    val fontSize: Flow<EditorFontSize> = preferences.map { stored ->
        stored[FONT_SIZE_KEY]?.let { value ->
            EditorFontSize.entries.firstOrNull { it.name == value }
        } ?: EditorFontSize.Standard
    }

    val autoSave: Flow<Boolean> = preferences.map { stored ->
        stored[AUTO_SAVE_KEY] ?: true
    }

    suspend fun setTheme(value: AppThemePreference) {
        dataStore.edit { it[THEME_KEY] = value.name }
    }

    suspend fun setFontSize(value: EditorFontSize) {
        dataStore.edit { it[FONT_SIZE_KEY] = value.name }
    }

    suspend fun setAutoSave(value: Boolean) {
        dataStore.edit { it[AUTO_SAVE_KEY] = value }
    }

    private companion object {
        val THEME_KEY = stringPreferencesKey("app_theme")
        val FONT_SIZE_KEY = stringPreferencesKey("editor_font_size")
        val AUTO_SAVE_KEY = booleanPreferencesKey("editor_auto_save")
    }
}
