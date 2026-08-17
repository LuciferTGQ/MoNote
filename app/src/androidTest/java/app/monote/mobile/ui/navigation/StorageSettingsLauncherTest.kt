package app.monote.mobile.ui.navigation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageSettingsLauncherTest {
    @Test
    fun missingAppSpecificSettingsFallsBackToGeneralSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launched = mutableListOf<Intent>()

        launchAllFilesAccessSettings(context) { intent: Intent ->
            launched += intent
            if (launched.size == 1) throw ActivityNotFoundException("missing app page")
        }

        assertEquals(2, launched.size)
        assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, launched[0].action)
        assertEquals("package:${context.packageName}", launched[0].data.toString())
        assertEquals(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION, launched[1].action)
        assertNull(launched[1].data)
        launched.forEach { intent ->
            assertNotEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
