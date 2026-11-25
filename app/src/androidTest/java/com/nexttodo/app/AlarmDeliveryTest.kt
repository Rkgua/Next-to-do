package com.nexttodo.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.media.AudioAttributes
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AlarmDeliveryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test fun foregroundAlertOpensReminderPage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = context.applicationContext as Application
        val shown = CountDownLatch(1)
        var screen: Activity? = null
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is ReminderActivity) { screen = activity; shown.countDown() }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        val main = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            instrumentation.runOnMainSync {
                assertTrue(AlarmAlerts.show(context, AlarmKind.EVENT, "foreground-test", "前台提醒测试", "应直接显示提醒页面"))
            }
            assertTrue("No foreground reminder page", shown.await(45, TimeUnit.SECONDS))
        } finally {
            AlarmAlerts.cancel(context, "foreground-test")
            instrumentation.runOnMainSync { screen?.finish(); main.finish() }
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    private fun awaitNotification(key: String): Notification {
        val until = SystemClock.elapsedRealtime() + 90_000
        while (SystemClock.elapsedRealtime() < until) {
            manager.activeNotifications.firstOrNull { it.tag == key && it.id == AlarmAlerts.ID }?.let { return it.notification }
            SystemClock.sleep(250)
        }
        throw AssertionError("Alarm did not deliver: $key")
    }

    @Test fun alarmHasSoundVibrationFullScreenDismissAndIndependentIdentity() {
        val keys = listOf("integration-a", "integration-b")
        try {
            keys.forEach { assertTrue(AlarmAlerts.show(context, AlarmKind.EVENT, it, it, "响铃测试")) }
            val notification = awaitNotification(keys[0])
            val channel = manager.getNotificationChannel(notification.channelId)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
            assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
            assertNotNull(channel.sound)
            assertTrue(channel.shouldVibrate())
            assertTrue(notification.flags and Notification.FLAG_INSISTENT != 0)
            assertEquals(AlarmAlerts.TIMEOUT, notification.timeoutAfter)
            if (AlarmAlerts.fullScreenAllowed(context)) assertNotNull(notification.fullScreenIntent)
            notification.actions.first().actionIntent.send()
            val until = SystemClock.elapsedRealtime() + 10_000
            while (manager.activeNotifications.any { it.tag == keys[0] } && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100)
            assertFalse(manager.activeNotifications.any { it.tag == keys[0] })
            assertTrue(manager.activeNotifications.any { it.tag == keys[1] })
        } finally { keys.forEach { AlarmAlerts.cancel(context, it) } }
    }

    @Test fun actualEventAlarmReachesReceiverAndDoesNotReplay() {
        val event = Event(UUID.randomUUID().toString(), title = "事件到点回归测试",
            time = LocalDateTime.now().plusSeconds(15).toString(), reminderEnabled = true)
        val reminders = EventReminders(context)
        EventStore(context).use { store ->
            store.save(event, null)
            try {
                reminders.update(null, event)
                assertNotNull(context.getSystemService(AlarmManager::class.java).nextAlarmClock)
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand("input keyevent 223").close()
                val notification = awaitNotification(event.id)
                assertEquals(event.title, notification.extras.getString(Notification.EXTRA_TITLE))
                val posted = manager.activeNotifications.first { it.tag == event.id }.postTime
                reminders.deliver(event.id, event.time, store)
                assertEquals(1, manager.activeNotifications.count { it.tag == event.id })
                assertEquals(posted, manager.activeNotifications.first { it.tag == event.id }.postTime)
                assertEquals(event.time, context.getSharedPreferences("delivered_event_reminders", 0).getString(event.id, null))
            } finally {
                InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand("input keyevent 224").close()
                reminders.remove(event)
                store.delete(event)
            }
        }
    }

    @Test fun actualCountdownAlarmFinishesWithoutUiPolling() {
        val prefs = context.getSharedPreferences("pomodoro", Context.MODE_PRIVATE)
        val backup = prefs.all.toMap()
        val timer = PomodoroTimer(context)
        var testSession: String? = null
        try {
            timer.startFocus(1)
            testSession = prefs.getString("trackingSession", null)
            prefs.edit().putLong("elapsedDeadline", SystemClock.elapsedRealtime() + 15_000)
                .putLong("wallDeadline", System.currentTimeMillis() + 15_000).commit()
            timer.restore()
            val notification = awaitNotification("pomodoro-finished")
            assertEquals(PomodoroPhase.FOCUS.finished, notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(PomodoroPhase.SHORT_BREAK.name, prefs.getString("phase", null))
            assertEquals(0, prefs.getLong("wallDeadline", -1))
            val posted = manager.activeNotifications.first { it.tag == "pomodoro-finished" }.postTime
            timer.refresh()
            assertEquals(posted, manager.activeNotifications.first { it.tag == "pomodoro-finished" }.postTime)
        } finally {
            timer.reset()
            testSession?.let { FocusHistory.get(context).writableDatabase.delete("segments", "session=?", arrayOf(it)) }
            AlarmAlerts.cancel(context, "pomodoro-finished")
            prefs.edit().clear().apply {
                backup.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                } }
            }.commit()
            timer.restore()
        }
    }
}
