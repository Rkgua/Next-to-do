package com.nexttodo.app

import android.app.*
import android.content.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.*
import java.util.concurrent.Executors
import org.json.JSONArray

internal data class LiveSnapshot(val title: String, val detail: String, val target: String,
    val deadline: Long? = null, val started: Long? = null, val runningTimer: Boolean = false)

internal fun buildLiveSnapshot(now: LocalDateTime, table: Timetable?, events: List<Event>, timer: PomodoroState?,
    coursesEnabled: Boolean, tasksEnabled: Boolean, timerEnabled: Boolean): LiveSnapshot? {
    val zone = ZoneId.systemDefault()
    val millis = now.atZone(zone).toInstant().toEpochMilli()
    val today = now.toLocalDate()
    val pending = visibleEventLeaves(events.mapNotNull { it.onDate(today, today) }, events).count { !it.completed }
    val tasks = if (tasksEnabled) "今日还有 $pending 件待办" else ""
    if (timerEnabled && timer != null && (timer.running || timer.remaining < timer.total)) {
        return LiveSnapshot(if (timer.running) "${timer.phase.label}进行中" else "${timer.phase.label}已暂停",
            tasks.ifBlank { "点击管理番茄钟" }, "timer", if (timer.running) millis + timer.remaining else null,
            if (timer.running) millis + timer.remaining - timer.total else null, timer.running)
    }
    if (coursesEnabled && table != null) {
        val week = table.currentWeek(today)
        val todayCourses = table.courses.filter { it.weekday == today.dayOfWeek.value && it.takesPlaceIn(week) && week >= 1 }
        val sessions = todayCourses.mapNotNull { course ->
            val custom = Regex("(\\d{1,2}):(\\d{2})\\s*[-—–~～至]\\s*(\\d{1,2}):(\\d{2})").find(course.customTime)
            val clocks = if (custom != null) runCatching {
                LocalTime.of(custom.groupValues[1].toInt(), custom.groupValues[2].toInt()) to LocalTime.of(custom.groupValues[3].toInt(), custom.groupValues[4].toInt())
            }.getOrNull() else course.firstPeriod?.let { first -> course.lastPeriod?.let { last ->
                periodTimes.getOrNull(first - 1)?.first?.let { start -> periodTimes.getOrNull(last - 1)?.second?.let { end -> LocalTime.parse(start) to LocalTime.parse(end) } }
            } }
            clocks?.takeIf { it.second > it.first }?.let { Triple(course, today.atTime(it.first), today.atTime(it.second)) }
        }.sortedBy { it.second }
        val active = sessions.firstOrNull { now >= it.second && now < it.third }
        val next = sessions.firstOrNull { it.second > now }
        val remaining = sessions.count { it.third > now }
        val item = active ?: next
        if (item != null) {
            val (course, start, end) = item
            val title = if (active != null) "距下课" else if (sessions.any { it.third <= now }) "课间 · 距上课" else "距上课"
            return LiveSnapshot(title, listOf(course.name, course.room.takeIf { it.isNotBlank() }, "今日剩余 $remaining 项课程安排", tasks.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "),
                "courses", (if (active != null) end else start).atZone(zone).toInstant().toEpochMilli(),
                if (active != null) start.atZone(zone).toInstant().toEpochMilli() else null)
        }
        val untimed = todayCourses.filter { course -> sessions.none { it.first == course } }
        if (untimed.isNotEmpty()) return LiveSnapshot("今日课程 · 时间待确认",
            (untimed.map { it.name } + listOfNotNull(tasks.takeIf { it.isNotBlank() })).joinToString(" · "), "courses")
    }
    if (tasksEnabled) return LiveSnapshot(if (pending == 0) "今日安排已完成 ✓" else tasks,
        events.mapNotNull { it.onDate(today, today) }.sortedByDescending { it.pinned }.firstOrNull { !it.completed }?.title ?: "点击查看 Today", "today")
    return null
}

internal object LiveStatus {
    var snapshot by mutableStateOf<LiveSnapshot?>(null)
        private set
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var initialized = false
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    private var pending: Runnable? = null

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        listOf("live_status", "timetable", "pomodoro").forEach { name ->
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> request(context) }
            listeners.add(listener)
            context.getSharedPreferences(name, 0).registerOnSharedPreferenceChangeListener(listener)
        }
        request(context)
    }

    @Synchronized fun request(context: Context) {
        pending?.let(handler::removeCallbacks)
        val app = context.applicationContext
        pending = Runnable { executor.execute { runCatching { refresh(app) }.onFailure { android.util.Log.e("NextTodo", "Live status refresh failed", it) } } }
        handler.postDelayed(pending!!, 200)
    }

    @Synchronized fun refresh(context: Context) {
        val prefs = context.getSharedPreferences("live_status", 0)
        val manager = context.getSystemService(NotificationManager::class.java)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val alarm = PendingIntent.getBroadcast(context, 0, Intent(context, LiveStatusReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        if (!prefs.getBoolean("enabled", false) || !manager.areNotificationsEnabled()) {
            snapshot = null; manager.cancel(730); alarms.cancel(alarm); return
        }
        val tablePrefs = context.getSharedPreferences("timetable", 0)
        val table = runCatching {
            val rows = JSONArray(tablePrefs.getString("tables", "[]"))
            val tables = (0 until rows.length()).map { rows.getJSONObject(it) }
            val row = tables.find { it.getString("id") == tablePrefs.getString("selectedTable", null) } ?: tables.firstOrNull()
            row?.let { Timetable(it.getString("id"), it.getString("name"), LocalDate.parse(it.getString("firstDay")), it.getJSONArray("courses").readCourses()) }
        }.getOrNull()
        val now = LocalDateTime.now()
        val events = EventStore(context).use { it.readAll() }
        val state = buildLiveSnapshot(now, table, events, PomodoroTimer(context).refresh(),
            prefs.getBoolean("courses", true), prefs.getBoolean("tasks", true), prefs.getBoolean("timer", true))
        snapshot = state
        if (state == null) {
            manager.cancel(730)
            if (prefs.getBoolean("courses", true)) {
                val tomorrow = now.toLocalDate().plusDays(1).atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                scheduleWhileIdle(alarms, AlarmManager.RTC_WAKEUP, tomorrow, alarm)
            } else alarms.cancel(alarm)
            return
        }
        manager.createNotificationChannel(NotificationChannel("live_status", "实时状态", NotificationManager.IMPORTANCE_LOW))
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (state.target == "timer") open.putExtra(PomodoroTimer.OPEN_POMODORO, true)
        if (state.target == "courses") open.putExtra("OPEN_TIMETABLE", true)
        if (state.target == "today") open.putExtra("OPEN_TODAY", true)
        val content = PendingIntent.getActivity(context, 730, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(context, "live_status").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(state.title).setContentText(state.detail).setStyle(Notification.BigTextStyle().bigText(state.detail))
            .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_STATUS)
        if (state.target == "today" && state.title.contains("已完成")) builder.setOngoing(false).setTimeoutAfter(30_000)
        state.deadline?.let { builder.setWhen(it).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true) }
        // Only an explicitly started, running timer requests system promotion.
        if (Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1 && state.runningTimer)
            builder.setRequestPromotedOngoing(true)
        if (state.target == "timer") {
            val action = PendingIntent.getBroadcast(context, 731, Intent(context, LiveStatusReceiver::class.java)
                .setAction(if (state.runningTimer) "PAUSE_TIMER" else "RESUME_TIMER"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, if (state.runningTimer) "暂停" else "继续", action).build())
        }
        try { manager.notify(730, builder.build()) } catch (_: SecurityException) { }
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val boundary = state.deadline?.takeIf { it > System.currentTimeMillis() } ?: midnight
        scheduleWhileIdle(alarms, AlarmManager.RTC_WAKEUP, minOf(boundary, midnight), alarm)
    }
}

class NextTodoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LiveStatus.initialize(this)
        Thread { FocusPlanScheduler.reschedule(this) }.start()
    }
}

class LiveStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                if (intent.action == "PAUSE_TIMER") PomodoroTimer(context).setRunning(false)
                if (intent.action == "RESUME_TIMER") PomodoroTimer(context).setRunning(true)
                LiveStatus.refresh(context.applicationContext)
            } catch (exception: Exception) {
                android.util.Log.e("NextTodo", "Live status receiver failed", exception)
            } finally { pending.finish() }
        }.start()
    }
}
