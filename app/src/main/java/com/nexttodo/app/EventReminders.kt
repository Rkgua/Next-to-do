package com.nexttodo.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

internal fun scheduleWhileIdle(alarms: AlarmManager, type: Int, at: Long, operation: PendingIntent) {
    try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(type, at, operation)
            return
        }
    } catch (_: SecurityException) {
        // Exact-alarm access may be revoked between checking and scheduling.
    }
    alarms.setAndAllowWhileIdle(type, at, operation)
}

internal class EventReminders(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val deliveries = context.getSharedPreferences("delivered_event_reminders", Context.MODE_PRIVATE)

    fun update(previous: Event?, current: Event) = synchronized(lock) {
        if (previous != null &&
            (previous.time != current.time || previous.reminderEnabled != current.reminderEnabled)) {
            deliveries.edit().remove(current.id).apply()
            notifications.cancel(current.id, NOTIFICATION_ID)
        }
        if (current.completed) notifications.cancel(current.id, NOTIFICATION_ID)
        synchronize(current)
    }

    fun restore(events: List<Event>) = synchronized(lock) {
        events.filter(Event::reminderEnabled).forEach(::synchronize)
    }

    fun deliver(id: String, scheduledTime: String, store: EventStore) = synchronized(lock) {
        val event = store.findEvent(id) ?: return
        if (!event.reminderEnabled || event.completed || event.time != scheduledTime ||
            deliveries.getString(id, null) == scheduledTime) return
        val due = event.timeInMillis() ?: return
        if (due > System.currentTimeMillis()) {
            synchronize(event)
            return
        }
        if (!notifications.areNotificationsEnabled()) return
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "事件到点提醒", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .setData(eventUri(id))
                .putExtra(OPEN_EVENT, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(event.title)
            .setContentText("事件时间已到 · ${eventTimeLabel(event.time)}")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            notifications.notify(id, NOTIFICATION_ID, notification)
            deliveries.edit().putString(id, scheduledTime).apply()
        } catch (_: SecurityException) {
            // Notifications were disabled while preparing this reminder.
        }
    }

    private fun synchronize(event: Event) {
        val intent = Intent(context, EventReminderReceiver::class.java)
            .setData(eventUri(event.id))
            .putExtra(EXPECTED_TIME, event.time)
        val time = event.timeInMillis()
        if (!event.reminderEnabled || event.completed || time == null || time <= System.currentTimeMillis() ||
            deliveries.getString(event.id, null) == event.time) {
            val existing = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (existing != null) alarms.cancel(existing)
            return
        }
        val operation = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        scheduleWhileIdle(alarms, AlarmManager.RTC_WAKEUP, time, operation)
    }

    private fun eventUri(id: String): Uri = Uri.Builder()
        .scheme("nexttodo").authority("event-reminder").appendPath(id).build()

    companion object {
        const val OPEN_EVENT = "com.nexttodo.app.OPEN_EVENT"
        const val EXPECTED_TIME = "com.nexttodo.app.EXPECTED_EVENT_TIME"
        private const val CHANNEL = "event_reminders"
        private const val NOTIFICATION_ID = 1
        private val lock = Any()
    }
}

class EventReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        executor.execute {
            try {
                EventStore(context).use { store ->
                    val reminders = EventReminders(context)
                    val id = intent.data?.lastPathSegment
                    val expectedTime = intent.getStringExtra(EventReminders.EXPECTED_TIME)
                    if (id != null && expectedTime != null) {
                        reminders.deliver(id, expectedTime, store)
                    } else {
                        reminders.restore(store.readAll())
                    }
                }
            } catch (exception: Exception) {
                android.util.Log.e("NextToDo", "Unable to process event reminders", exception)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}
