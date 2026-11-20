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
        if (!busy) draft = event
    }

    fun edit(event: Event) = updateDraft(events.find { it.id == event.id } ?: event)

    fun clearDraft(day: LocalDate = LocalDate.now()) = updateDraft(newEvent(day))

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
        write("无法保存事件，请检查附件后重试") {
            val saved = withContext(Dispatchers.IO) {
                store.save(event.copy(completed = if (previous?.repetition == event.repetition) previous.completed else false,
                    completedDates = if (previous?.repetition == event.repetition) previous.completedDates else emptySet()), previous).also {
                    runCatching { reminders.update(previous, it) }.onFailure(::logReminderFailure)
                }
            }
            events = if (previous == null) listOf(saved) + events
            else events.map { if (it.id == saved.id) saved else it }
            draft = newEvent()
            savedEventId = saved.id
        }
    }

    fun complete(event: Event, completed: Boolean) = write("无法更新事件状态") {
        val base = events.firstOrNull { it.id == event.id } ?: return@write
        val occurrence = event.occurrenceDate ?: event.occurrenceStart(LocalDate.now()) ?: base.startDay()
        val updated = if (base.repetition == EventRepeat.NONE) base.copy(completed = completed)
            else base.copy(completedDates = if (completed) base.completedDates + occurrence else base.completedDates - occurrence)
        withContext(Dispatchers.IO) {
            store.save(updated, base)
            runCatching { reminders.update(event, updated) }.onFailure(::logReminderFailure)
        }
        events = events.map { if (it.id == event.id) updated else it }
    }

    fun pin(event: Event) = write("无法更新置顶状态") {
        val base = events.firstOrNull { it.id == event.id } ?: return@write
        val updated = base.copy(pinned = !base.pinned)
        withContext(Dispatchers.IO) { store.save(updated, base) }
        events = events.map { if (it.id == updated.id) updated else it }
    }

    fun delete(event: Event) = write("无法删除事件") {
        val base = events.firstOrNull { it.id == event.id } ?: return@write
        withContext(Dispatchers.IO) { reminders.remove(base); store.delete(base) }
        events = events.filterNot { it.id == base.id }
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
