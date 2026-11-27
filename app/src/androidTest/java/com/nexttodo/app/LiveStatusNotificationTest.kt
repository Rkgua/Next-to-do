package com.nexttodo.app

import android.app.Notification
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveStatusNotificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun courseNotificationShowsScheduleAndDetailsWithoutTaskNoise() {
        val state = LiveSnapshot("企业级应用开发（JavaEE）", "10:00–10:45 · 第3节\n地点 · 教学楼 301", "courses",
            deadline = System.currentTimeMillis() + 80 * 60_000, countdownLabel = "距上课")
        val notification = buildLiveStatusNotification(context, state)
        assertEquals(state.title, notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("10:00–10:45 · 第3节", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("距上课", notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString())
        assertEquals("详情", notification.actions.single().title.toString())
        assertTrue(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertNotNull(notification.getLargeIcon())
    }

    @Test fun pausedTimerHasStaticRemainingTimeAndResumeAction() {
        val notification = buildLiveStatusNotification(context, LiveSnapshot("专注已暂停", "本轮 25分钟\n已完成 1/4 轮专注", "timer",
            remainingMillis = 12 * 60_000L + 34_000))
        assertEquals("剩余 12:34", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("继续", "详情"), notification.actions.map { it.title.toString() })
        assertFalse(notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
    }
}
