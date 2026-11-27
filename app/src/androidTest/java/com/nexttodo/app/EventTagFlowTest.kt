package com.nexttodo.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EventTagFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectCustomCancelRemoveAndPersistTagsIndependently() {
        val tags = mutableStateOf<List<EventTag>>(emptyList())
        compose.setContent { MaterialTheme { Column { EventTagEditor(tags.value, true) { tags.value = it } } } }
        compose.onNodeWithTag("event-tag-URGENT").performClick().assertIsSelected()
        compose.onNodeWithTag("event-tag-IMPORTANT").performClick().assertIsSelected()
        compose.onNodeWithTag("event-tag-NORMAL").performClick().assertIsSelected().performClick().assertIsNotSelected()
        compose.onNodeWithTag("event-tag-OPTIONAL").performClick().assertIsSelected()
        compose.onNodeWithTag("event-tag-custom-add").performClick()
        compose.onNodeWithText("添加", substring = false).assertIsNotEnabled()
        compose.onNodeWithTag("event-tag-custom-name").performTextInput("  复习检查  ")
        compose.onNodeWithContentDescription("标签颜色 · 紫色").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("event-tag-custom-color").performScrollTo().performTextReplacement("INVALID")
        compose.onNodeWithText("添加", substring = false).assertIsNotEnabled()
        compose.onNodeWithTag("event-tag-custom-color").performTextReplacement("#00897B")
        compose.onNodeWithTag("event-tag-color-mode-text").performScrollTo().performClick()
        compose.onNodeWithContentDescription("文字颜色 · 白色").performScrollTo().performClick()
        compose.onNodeWithTag("event-tag-auto-text-color").assertIsOff()
        compose.onNodeWithTag("event-tag-custom-text-color").performScrollTo().performTextReplacement("#F1F5FC")
        compose.onNodeWithText("添加", substring = false).performClick()
        compose.onNodeWithContentDescription("移除标签 · 复习检查").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0xFF00897B.toInt(), tags.value.first { it.kind == EventTagKind.CUSTOM }.color)
            assertEquals(0xFFF1F5FC.toInt(), tags.value.first { it.kind == EventTagKind.CUSTOM }.textColor)
        }
        compose.onNodeWithContentDescription("编辑标签 · 复习检查").performClick()
        compose.onNodeWithTag("event-tag-custom-color").performScrollTo().assertTextContains("#00897B")
        compose.onNodeWithTag("event-tag-custom-name").performScrollTo().performTextReplacement("复习计划")
        compose.onNodeWithContentDescription("标签颜色 · 黄色").performScrollTo().performClick()
        compose.onNodeWithTag("event-tag-color-mode-text").performScrollTo().performClick()
        compose.onNodeWithTag("event-tag-custom-text-color").performScrollTo().assertTextContains("#F1F5FC")
        compose.onNodeWithTag("event-tag-custom-text-color").performTextReplacement("INVALID")
        compose.onNodeWithText("保存", substring = false).assertIsNotEnabled()
        compose.onNodeWithTag("event-tag-auto-text-color").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithContentDescription("文字颜色 · 黑色").performScrollTo().performClick()
        compose.onNodeWithTag("event-tag-custom-text-color").performScrollTo().performTextReplacement("#123456")
        compose.onNodeWithText("保存", substring = false).performClick()
        compose.onNodeWithContentDescription("移除标签 · 复习计划").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(4, tags.value.size)
            assertEquals(0xFFE7B63D.toInt(), tags.value.first { it.kind == EventTagKind.CUSTOM }.color)
            assertEquals(0xFF123456.toInt(), tags.value.first { it.kind == EventTagKind.CUSTOM }.textColor)
        }
        compose.onNodeWithTag("event-tag-custom-add").performClick()
        compose.onNodeWithTag("event-tag-custom-name").performTextInput("不应添加")
        compose.onNodeWithContentDescription("标签颜色 · 红色").performScrollTo().performClick()
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.runOnIdle {
            assertEquals(4, tags.value.size)
            assertFalse(tags.value.any { it.name == "不应添加" })
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Event(UUID.randomUUID().toString(), title = "标签保存验证", tags = tags.value)
        val child = root.newChild().copy(title = "独立子事件标签", tags = listOf(EventTag(EventTagKind.NORMAL)))
        EventStore(context).use { store ->
            try {
                store.change(listOf(root to null, child to null))
                val loaded = checkNotNull(store.findEvent(root.id))
                assertEquals(root.tags, loaded.tags)
                assertEquals(child.tags, store.findEvent(child.id)?.tags)
                assertEquals(0xFFE7B63D.toInt(), loaded.tags.first { it.kind == EventTagKind.CUSTOM }.color)
                assertEquals(0xFF123456.toInt(), loaded.tags.first { it.kind == EventTagKind.CUSTOM }.textColor)
                compose.onNodeWithContentDescription("移除标签 · 复习计划").performClick()
                compose.runOnIdle { assertFalse(tags.value.any { it.kind == EventTagKind.CUSTOM }) }
                store.save(loaded.copy(tags = tags.value), loaded)
                assertEquals(tags.value, store.findEvent(root.id)?.tags)
                assertEquals(child.tags, store.findEvent(child.id)?.tags)
                val saved = checkNotNull(store.findEvent(root.id))
                store.save(saved.copy(tags = emptyList()), saved)
                assertTrue(checkNotNull(store.findEvent(root.id)).tags.isEmpty())
            } finally {
                store.change(emptyList(), store.readAll().filter { it.id == root.id || it.id == child.id })
            }
        }
    }
}
