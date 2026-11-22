package com.nexttodo.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import java.time.ZonedDateTime
import org.json.JSONArray
import org.json.JSONObject

internal object FocusPlanScheduler {
    @Synchronized fun read(context: Context): List<FocusPlan> = runCatching {
        val array = JSONArray(context.getSharedPreferences("focus_plans", 0).getString("plans", "[]"))
        (0 until array.length()).mapNotNull { index -> runCatching {
            val entry = array.getJSONObject(index)
            FocusPlan(entry.getString("id"), entry.getString("title"), entry.getInt("hour"), entry.getInt("minute"),
                entry.getInt("duration"), entry.getString("days").split(',').map(String::toInt).toSet(), entry.getBoolean("enabled"))
        }.getOrNull() }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    @Synchronized fun save(context: Context, plans: List<FocusPlan>) {
        require(plans.size <= 100)
        val alarms = context.getSystemService(AlarmManager::class.java)
        read(context).forEach { alarms.cancel(operation(context, it.id)) }
        val json = JSONArray().apply { plans.forEach { plan -> put(JSONObject().put("id", plan.id)
            .put("title", plan.title).put("hour", plan.hour).put("minute", plan.minute).put("duration", plan.duration)
            .put("days", plan.days.sorted().joinToString(",")).put("enabled", plan.enabled)) } }
        context.getSharedPreferences("focus_plans", 0).edit(commit = true) { putString("plans", json.toString()) }
        reschedule(context)
    }

    @Synchronized fun reschedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        read(context).forEach { plan ->
            val operation = operation(context, plan.id)
            alarms.cancel(operation)
            nextFocusPlan(plan, ZonedDateTime.now())?.let {
                scheduleWhileIdle(alarms, AlarmManager.RTC_WAKEUP, it.toInstant().toEpochMilli(), operation)
            }
        }
    }

    private fun operation(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, FocusPlanReceiver::class.java).setAction("FOCUS_PLAN")
            .setData("nexttodo://focus-plan/$id".toUri()).putExtra("planId", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    @Synchronized fun fire(context: Context, id: String) {
        val plan = read(context).firstOrNull { it.id == id && it.enabled } ?: return
        val timer = PomodoroTimer(context)
        val busy = timer.refresh().running
        if (!busy) timer.startFocus(plan.duration)
        reschedule(context)
        LiveStatus.request(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("focus_plans", "专注计划提醒", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, id.hashCode(), Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(PomodoroTimer.OPEN_POMODORO, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, "focus_plans").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(plan.title).setContentText(if (busy) "计划时间到了，当前计时继续" else "${plan.duration} 分钟专注已开始")
            .setContentIntent(open).setAutoCancel(true).build()
        try { manager.notify(id.hashCode(), notification) } catch (_: SecurityException) { }
    }
}

class FocusPlanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                if (intent.action == "FOCUS_PLAN") intent.getStringExtra("planId")?.let { FocusPlanScheduler.fire(context, it) }
                else FocusPlanScheduler.reschedule(context)
            } finally { pending.finish() }
        }.start()
    }
}
