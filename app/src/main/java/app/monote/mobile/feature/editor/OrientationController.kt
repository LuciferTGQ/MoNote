package app.monote.mobile.feature.editor

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class OrientationPreference {
    FollowSystem,
    Portrait,
    Landscape,
}

fun requestedOrientationFor(preference: OrientationPreference): Int = when (preference) {
    OrientationPreference.FollowSystem -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    OrientationPreference.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    OrientationPreference.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
}

fun applyOrientationPreference(activity: Activity, preference: OrientationPreference) {
    val requested = requestedOrientationFor(preference)
    if (activity.requestedOrientation != requested) activity.requestedOrientation = requested
}

class OrientationPreferenceStore(private val dataStore: DataStore<Preferences>) {
    val preference: Flow<OrientationPreference> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences ->
            preferences[KEY]?.let { stored ->
                OrientationPreference.entries.firstOrNull { it.name == stored }
            } ?: OrientationPreference.FollowSystem
        }

    val splitRatio: Flow<Float> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences -> normalizedSplitRatio(preferences[SPLIT_RATIO_KEY]) }

    suspend fun set(preference: OrientationPreference) {
        dataStore.edit { it[KEY] = preference.name }
    }

    suspend fun setSplitRatio(ratio: Float) {
        dataStore.edit { it[SPLIT_RATIO_KEY] = normalizedSplitRatio(ratio) }
    }

    private companion object {
        val KEY = stringPreferencesKey("editor_orientation")
        val SPLIT_RATIO_KEY = floatPreferencesKey("editor_split_ratio")
    }
}

internal fun normalizedSplitRatio(value: Float?): Float =
    value?.takeIf(Float::isFinite)?.coerceIn(0.25f, 0.75f) ?: 0.5f
