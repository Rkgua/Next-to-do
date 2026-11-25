package com.nexttodo.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.Executors
import java.time.LocalDateTime
import java.time.ZoneId

internal fun scheduleWhileIdle(alarms: AlarmManager, type: Int, at: Long, operation: PendingIntent,
    alarmClock: PendingIntent? = null) {
    try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            if (alarmClock != null) {
                val wallTime = if (type == AlarmManager.ELAPSED_REALTIME_WAKEUP)
                    System.currentTimeMillis() + at - SystemClock.elapsedRealtime() else at
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(wallTime, alarmClock), operation)
            } else alarms.setExactAndAllowWhileIdle(type, at, operation)
            return
        }
    } catch (_: SecurityException) {
        // Exact-alarm access may be revoked between checking and scheduling.
    }
    alarms.setAndAllowWhileIdle(type, at, operation)
}

internal fun scheduleUserAlarm(context: Context, alarms: AlarmManager, type: Int, at: Long, operation: PendingIntent) {
    val open = PendingIntent.getActivity(context, 743, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    scheduleWhileIdle(alarms, type, at, operation, alarmClock = open)
}

internal class EventReminders(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val deliveries = context.getSharedPreferences("delivered_event_reminders", Context.MODE_PRIVATE)

    fun update(previous: Event?, current: Event) = synchronized(lock) {
        if (previous != null &&
            (previous.time != current.time || previous.reminderEnabled != current.reminderEnabled ||
                previous.repetition != current.repetition || previous.repeatDays != current.repeatDays ||
                previous.repeatInterval != current.repeatInterval || previous.repeatWorkDays != current.repeatWorkDays)) {
            deliveries.edit().remove(current.id).apply()
            notifications.cancel(current.id, NOTIFICATION_ID)
            AlarmAlerts.cancel(context, current.id)
        }
        if (current.completed) {
            notifications.cancel(current.id, NOTIFICATION_ID)
            AlarmAlerts.cancel(context, current.id)
        }
        synchronize(current)
    }

    fun restore(events: List<Event>) = synchronized(lock) {
        events.filter(Event::reminderEnabled).forEach(::synchronize)
    }

    fun remove(event: Event) = synchronized(lock) {
        val pending = PendingIntent.getBroadcast(context, 0,
            Intent(context, EventReminderReceiver::class.java).setData(eventUri(event.id)),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        pending?.let { alarms.cancel(it); it.cancel() }
        notifications.cancel(event.id, NOTIFICATION_ID)
        AlarmAlerts.cancel(context, event.id)
        deliveries.edit().remove(event.id).apply()
    }

    fun deliver(id: String, scheduledTime: String, store: EventStore) = synchronized(lock) {
        val event = store.findEvent(id) ?: return
        val occurrence = runCatching { LocalDateTime.parse(scheduledTime) }.getOrNull() ?: return
        val displayed = event.onDate(occurrence.toLocalDate()) ?: return
        if (displayed.occurrenceDate != occurrence.toLocalDate() || !event.reminderEnabled || displayed.completed || event.time.isBlank() ||
            LocalDateTime.parse(event.time).toLocalTime() != occurrence.toLocalTime() ||
            deliveries.getString(id, null) == scheduledTime) return
        val due = occurrence.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (due > System.currentTimeMillis()) {
            synchronize(event)
            return
        }
        if (AlarmAlerts.show(context, AlarmKind.EVENT, id, event.title,
                "事件时间已到 · ${eventTimeLabel(scheduledTime)}", id)) {
            deliveries.edit().putString(id, scheduledTime).apply()
        }
        // Continue recurring reminders even if this delivery was blocked by notification settings.
        synchronize(event, catchUp = false)
    }

    private fun synchronize(event: Event, catchUp: Boolean = true) {
        val now = LocalDateTime.now()
        val next = if (catchUp) event.reminderToSchedule(now, deliveries.getString(event.id, null))
            else event.nextReminderAfter(now)
        val intent = Intent(context, EventReminderReceiver::class.java)
            .setData(eventUri(event.id))
            .putExtra(EXPECTED_TIME, next?.toString())
        val time = next?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
        if (time == null) {
            val existing = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (existing != null) alarms.cancel(existing)
            return
        }
        val operation = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        scheduleUserAlarm(context, alarms, AlarmManager.RTC_WAKEUP, maxOf(time, System.currentTimeMillis() + 100), operation)
    }

    private fun eventUri(id: String): Uri = Uri.Builder()
        .scheme("nexttodo").authority("event-reminder").appendPath(id).build()

    companion object {
        const val OPEN_EVENT = "com.nexttodo.app.OPEN_EVENT"
        const val EXPECTED_TIME = "com.nexttodo.app.EXPECTED_EVENT_TIME"
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
