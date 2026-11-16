package com.nexttodo.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat

internal enum class PomodoroPhase(val label: String, val finished: String) {
    FOCUS("专注", "专注结束，可以休息了"),
    SHORT_BREAK("短休息", "短休息结束，可以开始下一轮专注"),
    LONG_BREAK("长休息", "长休息结束，可以开始新一组专注"),
}

internal data class PomodoroDurations(val focus: Int = 25, val short: Int = 5, val long: Int = 15) {
    fun milliseconds(phase: PomodoroPhase): Long = 60_000L * when (phase) {
        PomodoroPhase.FOCUS -> focus
        PomodoroPhase.SHORT_BREAK -> short
        PomodoroPhase.LONG_BREAK -> long
    }
}

internal data class PomodoroState(
    val phase: PomodoroPhase,
    val completedFocus: Int,
    val remaining: Long,
    val running: Boolean,
    val durations: PomodoroDurations,
) {
    val total: Long get() = durations.milliseconds(phase)
}

internal class PomodoroTimer(private val context: Context) {
    private val preferences = context.getSharedPreferences("pomodoro", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private val alarmIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, PomodoroAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    fun refresh(): PomodoroState {
        val durations = PomodoroDurations(
            preferences.getInt("focus", 25),
            preferences.getInt("short", 5),
            preferences.getInt("long", 15),
        )
        val phase = PomodoroPhase.entries.firstOrNull { it.name == preferences.getString("phase", null) }
            ?: PomodoroPhase.FOCUS
        val completed = preferences.getInt("completed", 0)
        val running = preferences.getLong("wallDeadline", 0) > 0
        val remaining = if (running) {
            if (bootCount >= 0 && preferences.getInt("bootCount", -2) == bootCount) {
                preferences.getLong("elapsedDeadline", 0) - SystemClock.elapsedRealtime()
            } else {
                preferences.getLong("wallDeadline", 0) - System.currentTimeMillis()
            }
        } else {
            preferences.getLong("remaining", durations.milliseconds(phase))
        }
        val state = PomodoroState(phase, completed, remaining, running, durations)
        if (!running || remaining > 0) return state

        val advanced = nextPhase(state)
        save(advanced)
        notifyFinished(phase)
        return advanced
    }

    fun restore(): PomodoroState {
        val state = refresh()
        if (state.running) save(state)
        return state
    }

    fun setRunning(running: Boolean): PomodoroState = refresh().let { current ->
        if (current.running == running) current else current.copy(running = running).also(::save)
    }

    fun reset(): PomodoroState = refresh().let { current ->
        current.copy(remaining = current.total, running = false).also(::save)
    }

    fun skipBreak(): PomodoroState = refresh().let { current ->
        if (current.phase == PomodoroPhase.FOCUS) current else nextPhase(current).also(::save)
    }

    fun setDurations(durations: PomodoroDurations): PomodoroState {
        val current = refresh()
        if (current.running) return current
        return current.copy(durations = durations, remaining = durations.milliseconds(current.phase))
            .also(::save)
    }

    private fun nextPhase(current: PomodoroState): PomodoroState {
        val completed = when (current.phase) {
            PomodoroPhase.FOCUS -> current.completedFocus + 1
            PomodoroPhase.SHORT_BREAK -> current.completedFocus
            PomodoroPhase.LONG_BREAK -> 0
        }
        val phase = when {
            current.phase != PomodoroPhase.FOCUS -> PomodoroPhase.FOCUS
            completed >= 4 -> PomodoroPhase.LONG_BREAK
            else -> PomodoroPhase.SHORT_BREAK
        }
        return PomodoroState(phase, completed, current.durations.milliseconds(phase), false, current.durations)
    }

    private fun save(state: PomodoroState) {
        val elapsedDeadline = SystemClock.elapsedRealtime() + state.remaining
        preferences.edit()
            .putString("phase", state.phase.name)
            .putInt("completed", state.completedFocus)
            .putInt("focus", state.durations.focus)
            .putInt("short", state.durations.short)
            .putInt("long", state.durations.long)
            .putLong("remaining", state.remaining)
            .putInt("bootCount", bootCount)
            .putLong("elapsedDeadline", if (state.running) elapsedDeadline else 0)
            .putLong("wallDeadline", if (state.running) System.currentTimeMillis() + state.remaining else 0)
            .apply()
        if (!state.running) {
            alarms.cancel(alarmIntent)
            return
        }
        scheduleWhileIdle(alarms, AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedDeadline, alarmIntent)
    }

    private fun notifyFinished(phase: PomodoroPhase) {
        if (!notificationManager.areNotificationsEnabled()) return
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "番茄钟结束提醒", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(OPEN_POMODORO, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("番茄钟")
            .setContentText(phase.finished)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            notificationManager.notify(1, notification)
        } catch (_: SecurityException) {
            // The notification permission can be revoked after the status check.
        }
    }

    companion object {
        const val OPEN_POMODORO = "com.nexttodo.app.OPEN_POMODORO"
        private const val CHANNEL = "pomodoro"
    }
}

class PomodoroAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val timer = PomodoroTimer(context)
        if (intent.action == null) timer.refresh() else timer.restore()
    }
}
