package com.nexttodo.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderSettingsTest {
    @Test fun missingOemPageUsesAvailableFallbackOrReportsUnavailable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var result: ReminderSettingsDestination? = null
        instrumentation.runOnMainSync {
            result = openReminderSettings(context, listOf(Intent().setComponent(
                ComponentName("com.nexttodo.missing.settings", "com.nexttodo.missing.settings.StartupActivity"))))
        }
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null))
        // ATD images may omit Settings completely. Real devices usually have app details.
        val expected = when {
            context.packageManager.resolveActivity(appDetails, 0) != null -> ReminderSettingsDestination.APP_DETAILS
            context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0) != null -> ReminderSettingsDestination.SYSTEM_SETTINGS
            else -> ReminderSettingsDestination.UNAVAILABLE
        }
        assertEquals(expected, result)
    }
}
