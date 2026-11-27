package com.nexttodo.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class EventViewModel(application: Application) : AndroidViewModel(application) {
    private val store = EventStore(application)
    private val reminders = EventReminders(application)
    private val settings = AppSettings(application)
    private val recognition = DeepSeekImageRecognition(application)

    var events by mutableStateOf<List<Event>>(emptyList())
        private set
    var draft by mutableStateOf(newEvent())
        private set
    var draftChildren by mutableStateOf<List<Event>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var recognizing by mutableStateOf(false)
        private set
    var loadingFailed by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var savedEventId by mutableStateOf<String?>(null)
        private set

    init {
        reload()
    }

    fun reload() = write("无法读取本地事件", onFailure = { loadingFailed = true }) {
        events = withContext(Dispatchers.IO) {
            store.readAll().also { current ->
                runCatching { reminders.restore(current) }.onFailure(::logReminderFailure)
            }
        }
        loadingFailed = false
    }

    fun updateDraft(event: Event) {
        if (busy) return
        val dateChanged = event.calendarDay != draft.calendarDay || event.time != draft.time
        draft = if (event.parentId != null && dateChanged) event.copy(followsParentDate = false) else event
        if (event.parentId == null) draftChildren = draftChildren.map { child ->
            if (child.followsParentDate) child.copy(day = event.calendarDay ?: LocalDate.now(),
                repetition = event.repetition, repeatDays = event.repeatDays,
                repeatInterval = event.repeatInterval, repeatWorkDays = event.repeatWorkDays) else child
        }
    }

    fun edit(event: Event) {
        if (busy) return
        draft = events.find { it.id == event.id } ?: event
        draftChildren = events.filter { it.parentId == event.id }
    }

    fun clearDraft(day: LocalDate = LocalDate.now()) {
        if (busy) return
        draft = newEvent(day)
        draftChildren = emptyList()
    }

    fun updateDraftChildren(children: List<Event>) {
        if (!busy && draft.parentId == null) draftChildren = children.map { it.copy(parentId = draft.id) }
    }

    fun resetDraft(day: LocalDate) {
        val parent = events.find { it.id == draft.parentId }
        if (parent == null) clearDraft(day) else addChild(parent)
    }

    fun addChild(parent: Event) {
        if (busy) return
        val base = events.find { it.id == parent.id } ?: return
        if (base.parentId != null) return
        draft = base.newChild()
        draftChildren = emptyList()
    }

    fun dismissMessage() {
        message = null
    }

    fun acknowledgeSave() {
        savedEventId = null
    }

    fun recognizeImage(attachment: Attachment) {
        if (busy) return
        draft = draft.copy(originalImage = attachment)
        val input = draft
        write("图片识别失败，请检查网络后重试") {
            recognizing = true
            try {
                draft = withContext(Dispatchers.IO) {
                    val key = settings.readDeepSeekKey()
                        ?: throw ImageRecognitionException("请先在「My」填写并保存 DeepSeek API 密钥")
                    recognition.recognize(input, attachment, key)
                }
                message = "识别完成，请核对后保存"
            } finally {
                recognizing = false
            }
        }
    }

    fun save() {
        val event = draft.copy(
            title = draft.title.trim(),
            description = draft.description.trim(),
            fields = draft.fields.map { it.copy(name = it.name.trim()) }
        )
        if (event.title.isBlank() || event.fields.any { it.name.isBlank() }) {
            message = if (event.title.isBlank()) "请输入事件名称" else "请输入属性名称"
            return
        }
        val previous = events.firstOrNull { it.id == event.id }
        event.validateSchedule()?.let { message = it; return }
        val children = draftChildren.map { it.copy(title = it.title.trim(), description = it.description.trim()) }
        if (children.any { it.title.isBlank() || it.fields.any { field -> field.name.isBlank() } }) {
            message = "请填写子事件名称和属性名称"; return
        }
        children.firstNotNullOfOrNull { it.validateSchedule() }?.let { message = it; return }
        if (event.parentId != null && events.none { it.id == event.parentId && it.parentId == null }) {
            message = "主事件已不存在，请重新选择"; return
        }
        write("无法保存事件，请检查附件后重试") {
            val updated = event.copy(completed = previous?.completed ?: false,
                completedDates = if (previous?.repetition == event.repetition) previous.completedDates else emptySet())
                .withChildProgress(children)
            val removed = if (event.parentId == null) events.filter { old -> old.parentId == event.id && children.none { it.id == old.id } } else emptyList()
            val updates = mutableListOf(updated)
            updates.addAll(children)
            if (event.parentId != null) {
                val current = events.filterNot { it.id == event.id } + updated
                current.find { it.id == event.parentId }?.let { parent ->
                    updates.add(parent.withChildProgress(current.filter { it.parentId == parent.id }))
                }
            }
            val added = updates.filter { value -> events.none { it.id == value.id } }
            val existing = events.filterNot { old -> removed.any { it.id == old.id } }
                .map { old -> updates.find { it.id == old.id } ?: old }
            val next = if (event.parentId != null && previous == null) existing.toMutableList().apply {
                val lastChild = indexOfLast { it.parentId == event.parentId }
                val position = if (lastChild >= 0) lastChild + 1 else (indexOfFirst { it.id == event.parentId } + 1).coerceAtLeast(0)
                addAll(position, added)
            } else added + existing
            val saved = withContext(Dispatchers.IO) {
                store.change(updates.map { value -> value to events.find { it.id == value.id } }, removed, next.map { it.id }).also { values ->
                    removed.forEach { runCatching { reminders.remove(it) }.onFailure(::logReminderFailure) }
                    values.forEach { value -> runCatching { reminders.update(events.find { it.id == value.id }, value) }.onFailure(::logReminderFailure) }
                }
            }
            events = next.map { value -> saved.find { it.id == value.id } ?: value }
            draft = newEvent()
            draftChildren = emptyList()
            savedEventId = updated.id
        }
    }

    fun complete(event: Event, completed: Boolean) = write("无法更新事件状态") {
        val updates = completionChanges(events, event, completed)
        withContext(Dispatchers.IO) {
            store.change(updates.map { value -> value to events.find { it.id == value.id } })
            updates.forEach { updated -> runCatching { reminders.update(events.find { it.id == updated.id }, updated) }.onFailure(::logReminderFailure) }
        }
        events = events.map { old -> updates.find { it.id == old.id } ?: old }
    }

    fun pin(event: Event) = write("无法更新置顶状态") {
        val base = events.firstOrNull { it.id == event.id } ?: return@write
        val updated = base.copy(pinned = !base.pinned)
        withContext(Dispatchers.IO) { store.save(updated, base) }
        events = events.map { if (it.id == updated.id) updated else it }
    }

    fun delete(event: Event, keepChildren: Boolean = false) = write("无法删除事件") {
        val base = events.firstOrNull { it.id == event.id } ?: return@write
        val children = events.filter { it.parentId == base.id }
        val deleted = listOf(base) + if (keepChildren) emptyList() else children
        val updates = if (keepChildren) children.map { it.copy(parentId = null, followsParentDate = false) }.toMutableList()
            else mutableListOf()
        base.parentId?.let { id ->
            val remaining = events.filter { it.parentId == id && it.id != base.id }
            val parent = events.find { it.id == id }
            if (parent != null && remaining.isNotEmpty()) updates.add(parent.withChildProgress(remaining))
        }
        withContext(Dispatchers.IO) {
            store.change(updates.map { value -> value to events.find { it.id == value.id } }, deleted)
            deleted.forEach { runCatching { reminders.remove(it) }.onFailure(::logReminderFailure) }
            updates.forEach { value -> runCatching { reminders.update(events.find { it.id == value.id }, value) }.onFailure(::logReminderFailure) }
        }
        events = events.filterNot { old -> deleted.any { it.id == old.id } }.map { old -> updates.find { it.id == old.id } ?: old }
    }

    fun moveEvent(id: String, targetId: String) {
        val reordered = reorderedEvents(events, id, targetId) ?: return
        write("无法保存事件顺序") {
            withContext(Dispatchers.IO) { store.saveOrder(reordered.map(Event::id)) }
            events = reordered
        }
    }

    private fun write(
        errorMessage: String,
        onFailure: () -> Unit = {},
        action: suspend () -> Unit,
    ) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                action()
                LiveStatus.request(getApplication())
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                android.util.Log.e("NextToDo", errorMessage, exception)
                onFailure()
                message = if (exception is ImageRecognitionException) exception.message else errorMessage
            } finally {
                busy = false
            }
        }
    }

    private fun logReminderFailure(exception: Throwable) {
        android.util.Log.e("NextToDo", "Unable to schedule event reminder", exception)
    }

    private fun newEvent(day: LocalDate = LocalDate.now()) = Event(id = UUID.randomUUID().toString(), day = day)
}
