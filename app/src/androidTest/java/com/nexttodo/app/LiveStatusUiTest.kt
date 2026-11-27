package com.nexttodo.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveStatusUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun courseDetailsAreStructuredAndRouteToTimetable() {
        val now = System.currentTimeMillis()
        val snapshot = mutableStateOf(LiveSnapshot("企业级应用开发（JavaEE）", "10:00–10:45 · 第3节\n地点 · 教学楼 301",
            "courses", deadline = now + 80 * 60_000L, countdownLabel = "距下课"))
        var opened: String? = null
        compose.setContent { MaterialTheme { LiveStatusChip(snapshot.value, now) { opened = it } } }
        compose.onNodeWithText("距下课 1小时20分钟").assertIsDisplayed().performClick()
        compose.onNodeWithText("企业级应用开发（JavaEE）").assertIsDisplayed()
        compose.onNodeWithText("10:00–10:45 · 第3节\n地点 · 教学楼 301").assertIsDisplayed()
        compose.onNodeWithText("详情").performClick()
        compose.runOnIdle { assertEquals("courses", opened) }
        compose.runOnIdle {
            snapshot.value = LiveSnapshot("专注已暂停", "本轮 25分钟\n已完成 1/4 轮专注", "timer", remainingMillis = 12 * 60_000L + 34_000)
        }
        compose.onNodeWithText("专注已暂停 12:34").assertIsDisplayed()
        compose.onNodeWithText("企业级应用开发（JavaEE）").assertDoesNotExist()
    }
}
