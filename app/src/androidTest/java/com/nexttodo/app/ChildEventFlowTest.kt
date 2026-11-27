package com.nexttodo.app

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChildEventFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun createChildrenFilterCompleteAndKeepChildrenOnDelete() {
        lateinit var model: EventViewModel
        lateinit var appearance: TodayAppearanceViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = EventViewModel(context.applicationContext as Application)
            appearance = TodayAppearanceViewModel(context.applicationContext as Application)
        }
        compose.waitUntil(10_000) { !model.busy }
        val oldDisplay = appearance.display
        val title = "层级验证-${UUID.randomUUID().toString().take(6)}"
        compose.runOnIdle { model.clearDraft(); appearance.changeDisplay(EventDisplay.TREE) }
        val rootId = model.draft.id
        val editing = mutableStateOf(true)
        val ids = mutableSetOf(rootId)
        try {
            compose.setContent {
                MaterialTheme {
                    if (editing.value && model.savedEventId == null) EventEditor(model.draft, !model.busy, false,
                        model::updateDraft, {}, model::save, { model.clearDraft() },
                        children = model.draftChildren, onChildrenChange = model::updateDraftChildren)
                    else TodayEventList(model.events.mapNotNull { it.onDate(LocalDate.now()) }, emptyList(), model, appearance,
                        onOpen = {}, onAddChild = {})
                }
            }
            compose.onNodeWithTag("event-field-事件名称").performTextInput(title)
            compose.onNodeWithText("＋ 添加子事件").performScrollTo().performClick()
            compose.onNodeWithText("完成编辑").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("请输入子事件名称").assertIsDisplayed()
            compose.onNode(hasTestTag("event-field-事件名称") and hasAnyAncestor(isDialog())).performTextInput("准备资料")
            compose.onNodeWithText("完成编辑").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5_000) { model.draftChildren.size == 1 }
            compose.onNodeWithText("＋ 添加子事件").performScrollTo().performClick()
            compose.onNode(hasTestTag("event-field-事件名称") and hasAnyAncestor(isDialog())).performTextInput("完成练习")
            compose.onNodeWithText("完成编辑").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.waitUntil(5_000) { model.draftChildren.size == 2 }
            ids.addAll(model.draftChildren.map { it.id })
            compose.onNodeWithText("保存事件").performClick()
            compose.waitUntil(10_000) { model.savedEventId == rootId && !model.busy }
            EventStore(context).use { store ->
                val children = store.readAll().filter { it.parentId == rootId }
                assertEquals(listOf("准备资料", "完成练习"), children.map { it.title })
                assertTrue(children.all { it.followsParentDate && it.calendarDay == LocalDate.now() })
            }
            compose.onNodeWithContentDescription("完成状态 · 准备资料").performScrollTo().performClick()
            compose.waitUntil(10_000) { !model.busy && model.events.any { it.id in ids && it.title == "准备资料" && it.completed } }
            compose.onNodeWithTag("event-filter-COMPLETED").performClick()
            compose.onNodeWithText(title).assertIsDisplayed()
            compose.onNodeWithText("完成练习", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("event-display-LIST").performClick()
            compose.onNodeWithText("准备资料").assertIsDisplayed()
            compose.onNodeWithTag("event-filter-PENDING").performClick()
            compose.onNodeWithText("完成练习").assertIsDisplayed()
            compose.onNodeWithTag("event-display-TREE").performClick()
            compose.onNodeWithTag("event-filter-ALL").performClick()
            compose.onNodeWithContentDescription("完成状态 · $title").performScrollTo().performClick()
            compose.onNodeWithText("完成整个事件？").assertIsDisplayed()
            compose.onNodeWithText("全部完成").performClick()
            compose.waitUntil(10_000) { !model.busy && model.events.filter { it.id in ids }.all { it.completed } }
            EventStore(context).use { assertTrue(it.readAll().filter { it.id in ids }.all { value -> value.completed }) }
            compose.onNodeWithContentDescription("完成状态 · 完成练习").performScrollTo().performClick()
            compose.waitUntil(10_000) { !model.busy && model.events.any { it.id == rootId && !it.completed } }
            compose.onNodeWithContentDescription("事件操作 · $title").performScrollTo().performClick()
            compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
            compose.onNodeWithText("保留子事件").performClick()
            compose.waitUntil(10_000) { !model.busy && model.events.none { it.id == rootId } }
            EventStore(context).use { store ->
                val kept = store.readAll().filter { it.id in ids }
                assertEquals(2, kept.size)
                assertTrue(kept.all { it.parentId == null && !it.followsParentDate })
            }
        } finally {
            EventStore(context).use { store -> store.change(emptyList(), store.readAll().filter { it.id in ids }) }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { appearance.changeDisplay(oldDisplay) }
        }
    }

    @Test fun invalidHierarchyRollsBackWholeGroup() {
        val root = Event(UUID.randomUUID().toString(), title = "事务验证")
        val child = root.newChild().copy(title = "子事件")
        EventStore(context).use { store ->
            try {
                store.change(listOf(root to null, child to null))
                try {
                    store.change(listOf(root.copy(title = "不应保存") to root,
                        child.copy(parentId = "missing-parent") to child))
                    fail("Invalid parent should reject transaction")
                } catch (_: IllegalArgumentException) { }
                assertEquals(root.title, store.findEvent(root.id)?.title)
                assertEquals(root.id, store.findEvent(child.id)?.parentId)
                try {
                    store.change(emptyList(), listOf(root))
                    fail("Parent deletion should not leave an orphan")
                } catch (_: IllegalArgumentException) { }
                assertNotNull(store.findEvent(root.id))
            } finally {
                store.change(emptyList(), store.readAll().filter { it.id == root.id || it.id == child.id })
            }
        }
    }
}
