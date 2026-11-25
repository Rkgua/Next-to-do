package com.nexttodo.app

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.lang.ref.WeakReference

internal enum class AlarmKind(val channel: String, val label: String, val legacy: String) {
    EVENT("event_alarm_v2", "事件响铃提醒", "event_reminders"),
    TIMER("timer_alarm_v2", "专注与休息结束响铃", "pomodoro"),
    PLAN("plan_alarm_v2", "专注计划响铃", "focus_plans"),
}

internal object AlarmAlerts {
    const val ID = 742
    const val TIMEOUT = 60_000L
    private var foreground = WeakReference<Activity>(null)

    fun resumed(activity: Activity) { foreground = WeakReference(activity) }
    fun paused(activity: Activity) { if (foreground.get() === activity) foreground.clear() }

    fun channel(context: Context, kind: AlarmKind): NotificationChannel {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.getNotificationChannel(kind.channel)?.let { return it }
        val old = manager.getNotificationChannel(kind.legacy)
        val channel = NotificationChannel(kind.channel, kind.label,
            old?.importance ?: NotificationManager.IMPORTANCE_HIGH).apply {
            description = "到点响铃和振动，最长一分钟；可打开提醒页面关闭"
            val customSound = (old != null && old.sound == null) ||
                (Build.VERSION.SDK_INT >= 30 && old?.hasUserSetSound() == true)
            setSound(if (customSound) old?.sound else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(if (customSound) old?.shouldVibrate() == true else true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        // Preserve a disabled or reduced-importance old channel instead of overriding user settings.
        manager.createNotificationChannel(channel)
        return manager.getNotificationChannel(kind.channel)
    }

    fun fullScreenAllowed(context: Context): Boolean = Build.VERSION.SDK_INT < 34 ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun show(context: Context, kind: AlarmKind, key: String, title: String, text: String,
        eventId: String? = null): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = channel(context, kind)
        if (!manager.areNotificationsEnabled() || channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        val data = Uri.Builder().scheme("nexttodo").authority("alarm").appendPath(key).build()
        val screen = Intent(context, ReminderActivity::class.java).setData(data)
            .putExtra("title", title).putExtra("text", text).putExtra("eventId", eventId)
            .putExtra("expires", System.currentTimeMillis() + TIMEOUT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(context, 0, screen,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val dismiss = PendingIntent.getBroadcast(context, 0,
            Intent(context, AlarmDismissReceiver::class.java).setData(data),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, kind.channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(TIMEOUT)
            .addAction(0, "停止提醒", dismiss)
        if (fullScreenAllowed(context)) builder.setFullScreenIntent(open, true)
        val notification = builder.build().apply {
            if (channel.sound != null && channel.importance >= NotificationManager.IMPORTANCE_HIGH) {
                flags = flags or Notification.FLAG_INSISTENT
            }
        }
        return try {
            manager.notify(key, ID, notification)
            Handler(Looper.getMainLooper()).post {
                foreground.get()?.takeUnless { it.isFinishing || it.isDestroyed }?.let { activity ->
                    // Only open directly while our own activity is resumed. Background launches use
                    // the system's full-screen notification permission and lock-screen policy.
                    runCatching { activity.startActivity(screen) }
                }
            }
            true
        } catch (_: SecurityException) { false }
    }

    fun cancel(context: Context, key: String) {
        context.getSystemService(NotificationManager::class.java).cancel(key, ID)
    }
}

class AlarmDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.data?.lastPathSegment?.let { AlarmAlerts.cancel(context, it) }
    }
}

internal fun scheduleReminderTest(context: Context, kind: AlarmKind) {
    val operation = PendingIntent.getBroadcast(context, kind.ordinal,
        Intent(context, ReminderTestReceiver::class.java).putExtra("kind", kind.name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    scheduleUserAlarm(context, context.getSystemService(android.app.AlarmManager::class.java),
        android.app.AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5_000, operation)
}

class ReminderTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = AlarmKind.entries.firstOrNull { it.name == intent.getStringExtra("kind") } ?: return
        AlarmAlerts.show(context, kind, "test:${kind.name}", "${kind.label}测试", "这是测试提醒，可点击下方按钮停止；不会修改事件或计时。")
    }
}
