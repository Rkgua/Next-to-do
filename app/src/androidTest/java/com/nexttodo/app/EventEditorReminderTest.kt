package com.nexttodo.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventEditorReminderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun blankDraftShowsReminderWithoutTogglingDuration() {
        val draft = mutableStateOf(Event("blank-draft"))
        compose.setContent {
            MaterialTheme {
                EventEditor(draft.value, true, false, { draft.value = it }, {}, {}, {})
            }
        }
        compose.onNodeWithTag("event-reminder-switch").performScrollTo().assertIsDisplayed().assertIsNotEnabled().assertIsOff()
        compose.onNodeWithText("请先设置事件时间，再开启到点提醒").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertFalse(draft.value.isDuration) }

        // Match the state change emitted when the date/time picker confirms a future time.
        compose.runOnIdle { draft.value = draft.value.copy(time = LocalDateTime.now().plusHours(2).toString()) }
        compose.onNodeWithTag("event-reminder-switch").performScrollTo().assertIsEnabled().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(draft.value.reminderEnabled); assertFalse(draft.value.isDuration) }
    }

    @Test fun durationToggleAndClearingTimeKeepReminderControlVisible() {
        val draft = mutableStateOf(Event("timed-draft", time = LocalDateTime.now().plusHours(2).toString()))
        compose.setContent {
            MaterialTheme {
                EventEditor(draft.value, true, false, { draft.value = it }, {}, {}, {})
            }
        }
        compose.onNodeWithTag("event-reminder-switch").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithContentDescription("切换持续事件").performScrollTo().performClick()
        compose.onNodeWithText("开始时提醒").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("event-reminder-switch").assertIsOn()
        compose.onNodeWithContentDescription("切换持续事件").performScrollTo().performClick()
        compose.onNodeWithText("到点提醒").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("event-reminder-switch").assertIsOn()
        compose.onNodeWithText("清除").performScrollTo().performClick()
        compose.onNodeWithTag("event-reminder-switch").performScrollTo().assertIsDisplayed().assertIsNotEnabled().assertIsOff()
        compose.runOnIdle { assertTrue(draft.value.time.isBlank()); assertFalse(draft.value.reminderEnabled) }
    }
}
