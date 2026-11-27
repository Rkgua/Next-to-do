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
        val notification = buildLiveStatusNotification(context, state)
        try { manager.notify(730, notification) } catch (_: SecurityException) { }
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val boundary = state.deadline?.takeIf { it > System.currentTimeMillis() } ?: midnight
        scheduleWhileIdle(alarms, AlarmManager.RTC_WAKEUP, minOf(boundary, midnight), alarm)
    }
}

internal fun buildLiveStatusNotification(context: Context, state: LiveSnapshot): Notification {
    val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    if (state.target == "timer") open.putExtra(PomodoroTimer.OPEN_POMODORO, true)
    if (state.target == "courses") open.putExtra("OPEN_TIMETABLE", true)
    if (state.target == "today") open.putExtra("OPEN_TODAY", true)
    val content = PendingIntent.getActivity(context, 730, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val builder = Notification.Builder(context, "live_status").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle(state.title).setContentText(state.detail.lineSequence().first())
        .setStyle(Notification.BigTextStyle().bigText(state.detail))
        .setContentIntent(content).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_STATUS)
    if (state.target == "courses") builder.setSubText(state.countdownLabel)
        .setCategory(Notification.CATEGORY_EVENT)
        .setLargeIcon(android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.nexttodo_logo))
    if (state.target == "timer" && !state.runningTimer) builder
        .setContentText("剩余 ${liveRemainingLabel(state.remainingMillis ?: 0, showSeconds = true)}")
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
    builder.addAction(Notification.Action.Builder(null, "详情", content).build())
    return builder.build()
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
