package com.nexttodo.app

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import java.util.UUID
import java.util.concurrent.Executors
import androidx.core.content.edit

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
    private val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private val alarmIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, PomodoroAlarmReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    fun refresh(): PomodoroState = synchronized(timerLock) {
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
        if (!running) return@synchronized state
        if (remaining > 0) {
            if (preferences.getString("trackingSegment", null) == null) beginTracking(state)
            else if (preferences.getString("trackingKind", null) != trackingKind(phase).name) {
                recordPart(state, false)
                beginTracking(state)
            }
            return@synchronized state
        }
        recordPart(state, true)
        val advanced = nextPhase(state)
        save(advanced, clearSession = true)
        notifyFinished(phase)
        advanced
    }

    fun restore(): PomodoroState = synchronized(timerLock) {
        refresh().also { if (it.running) save(it) }
    }

    fun setRunning(running: Boolean): PomodoroState = synchronized(timerLock) {
        val current = refresh()
        if (current.running == running) current else {
            if (!running) recordPart(current, false)
            current.copy(running = running).also { save(it) }
        }
    }

    fun reset(): PomodoroState = synchronized(timerLock) {
        val current = refresh()
        if (current.running) recordPart(current, false)
        current.copy(remaining = current.total, running = false).also { save(it, clearSession = true) }
    }

    fun skipBreak(): PomodoroState = synchronized(timerLock) {
        val current = refresh()
        if (current.phase == PomodoroPhase.FOCUS) current else {
            if (current.running) recordPart(current, false)
            nextPhase(current).also { save(it, clearSession = true) }
        }
    }

    fun setDurations(durations: PomodoroDurations): PomodoroState = synchronized(timerLock) {
        require(listOf(durations.focus, durations.short, durations.long).all { it in 1..180 })
        val current = refresh()
        if (current.running) current else current.copy(durations = durations,
            remaining = durations.milliseconds(current.phase)).also { save(it, clearSession = true) }
    }

    fun startFocus(minutes: Int): PomodoroState = startPhase(PomodoroPhase.FOCUS, minutes)
    fun startRest(minutes: Int = 5): PomodoroState = startPhase(PomodoroPhase.SHORT_BREAK, minutes)

    private fun startPhase(phase: PomodoroPhase, minutes: Int): PomodoroState = synchronized(timerLock) {
        require(minutes in 1..180)
        val current = refresh()
        if (current.running) recordPart(current, false)
        val durations = if (phase == PomodoroPhase.FOCUS) current.durations.copy(focus = minutes)
            else current.durations.copy(short = minutes)
        current.copy(phase = phase, durations = durations, remaining = minutes * 60_000L, running = true)
            .also { save(it, clearSession = true) }
    }

    fun trackScreenPin(enabled: Boolean) = synchronized(timerLock) {
        preferences.edit { putBoolean("recordPin", enabled) }
        refresh()
    }

    fun liveSegment(): FocusSegment? = synchronized(timerLock) {
        val state = refresh()
        if (state.running) segment(state, false) else null
    }

    private fun trackingKind(phase: PomodoroPhase): FocusKind = when {
        phase != PomodoroPhase.FOCUS -> FocusKind.REST
        preferences.getBoolean("recordPin", false) && context.getSystemService(ActivityManager::class.java)
            .lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE -> FocusKind.LOCK
        else -> FocusKind.FOCUS
    }

    private fun beginTracking(state: PomodoroState) {
        preferences.edit {
            putString("trackingSession", preferences.getString("trackingSession", null) ?: UUID.randomUUID().toString())
            putString("trackingSegment", UUID.randomUUID().toString())
            putString("trackingKind", trackingKind(state.phase).name)
            putLong("trackingRemaining", state.remaining.coerceAtLeast(0))
        }
    }

    private fun segment(state: PomodoroState, completed: Boolean): FocusSegment? {
        val id = preferences.getString("trackingSegment", null) ?: return null
        val session = preferences.getString("trackingSession", null) ?: return null
        val checkpoint = preferences.getLong("trackingRemaining", state.remaining.coerceAtLeast(0))
        val duration = (checkpoint - state.remaining.coerceAtLeast(0)).coerceIn(0, checkpoint.coerceAtLeast(0))
        if (duration <= 0) return null
        val end = System.currentTimeMillis() + state.remaining.coerceAtMost(0)
        val kind = FocusKind.entries.firstOrNull { it.name == preferences.getString("trackingKind", null) }
            ?: trackingKind(state.phase)
        return FocusSegment(id, session, end - duration, end, kind, completed)
    }

    private fun recordPart(state: PomodoroState, completed: Boolean) {
        segment(state, completed)?.let { FocusHistory.get(context).record(it) }
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

    private fun save(state: PomodoroState, clearSession: Boolean = false) {
        val elapsedDeadline = SystemClock.elapsedRealtime() + state.remaining
        val editor = preferences.edit()
        if (clearSession) editor.remove("trackingSession")
        if (!state.running) editor.remove("trackingSegment")
        else if (clearSession || preferences.getString("trackingSegment", null) == null) {
            editor.putString("trackingSession", if (clearSession) UUID.randomUUID().toString()
                else preferences.getString("trackingSession", null) ?: UUID.randomUUID().toString())
                .putString("trackingSegment", UUID.randomUUID().toString())
                .putString("trackingKind", trackingKind(state.phase).name)
                .putLong("trackingRemaining", state.remaining)
        }
        editor
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
        scheduleUserAlarm(context, alarms, AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedDeadline, alarmIntent)
    }

    private fun notifyFinished(phase: PomodoroPhase) {
        AlarmAlerts.show(context, AlarmKind.TIMER, "pomodoro-finished", "番茄钟", phase.finished)
    }

    companion object {
        private val timerLock = Any()
        const val OPEN_POMODORO = "com.nexttodo.app.OPEN_POMODORO"
    }
}

class PomodoroAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        executor.execute {
            try {
                val timer = PomodoroTimer(context)
                if (intent.action == null) timer.refresh() else timer.restore()
            } catch (exception: Exception) {
                android.util.Log.e("NextToDo", "Unable to process timer alarm", exception)
            } finally { pending.finish() }
        }
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}
