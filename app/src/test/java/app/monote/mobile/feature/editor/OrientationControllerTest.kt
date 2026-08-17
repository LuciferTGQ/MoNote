package app.monote.mobile.feature.editor

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class OrientationControllerTest {
    @Test
    fun preferencesMapToTheThreeSupportedAndroidModes() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            requestedOrientationFor(OrientationPreference.FollowSystem),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            requestedOrientationFor(OrientationPreference.Portrait),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            requestedOrientationFor(OrientationPreference.Landscape),
        )
    }

    @Test
    fun splitRatioUsesASafeRememberedRange() {
        assertEquals(0.5f, normalizedSplitRatio(null))
        assertEquals(0.5f, normalizedSplitRatio(Float.NaN))
        assertEquals(0.25f, normalizedSplitRatio(0.1f))
        assertEquals(0.6f, normalizedSplitRatio(0.6f))
        assertEquals(0.75f, normalizedSplitRatio(0.9f))
    }
}
