package com.nexttodo.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class ReminderActivity : ComponentActivity() {
    private var alert by mutableStateOf(Intent())
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { dismiss() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        show(intent)
        setContent {
            val dark = when (AppSettings(this).theme) {
                AppTheme.DARK -> true
                AppTheme.LIGHT -> false
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                BackHandler { dismiss() }
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("时间到了", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(20.dp))
                        Text(alert.getStringExtra("title").orEmpty(), style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(16.dp))
                        Text(alert.getStringExtra("text").orEmpty(), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(32.dp))
                        Button(onClick = { dismiss() }, modifier = Modifier.fillMaxWidth()) { Text("知道了，停止提醒") }
                        TextButton(onClick = {
                            val open = Intent(this@ReminderActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            alert.getStringExtra("eventId")?.let { open.putExtra(EventReminders.OPEN_EVENT, it) }
                                ?: open.putExtra(PomodoroTimer.OPEN_POMODORO, true)
                            dismiss()
                            startActivity(open)
                        }) { Text(if (alert.hasExtra("eventId") && alert.getStringExtra("eventId") != null) "查看事件" else "打开专注") }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    private fun show(intent: Intent) {
        alert = intent
        handler.removeCallbacks(expire)
        val remaining = intent.getLongExtra("expires", 0) - System.currentTimeMillis()
        if (remaining <= 0) dismiss() else handler.postDelayed(expire, remaining)
    }

    private fun dismiss() {
        alert.data?.lastPathSegment?.let { AlarmAlerts.cancel(this, it) }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(expire)
        super.onDestroy()
    }
}
