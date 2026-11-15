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

    var events by mutableStateOf<List<Event>>(emptyList())
        private set
    var draft by mutableStateOf(newEvent())
        private set
    var busy by mutableStateOf(false)
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
        events = withContext(Dispatchers.IO) { store.readAll() }
        loadingFailed = false
    }

    fun updateDraft(event: Event) {
        if (!busy) draft = event
    }

    fun edit(event: Event) = updateDraft(event)

    fun clearDraft(day: LocalDate = LocalDate.now()) = updateDraft(newEvent(day))

    fun dismissMessage() {
        message = null
    }

    fun acknowledgeSave() {
        savedEventId = null
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
        write("无法保存事件，请检查附件后重试") {
            val saved = withContext(Dispatchers.IO) {
                store.save(event.copy(completed = previous?.completed ?: false), previous)
            }
            events = if (previous == null) listOf(saved) + events
            else events.map { if (it.id == saved.id) saved else it }
            draft = newEvent()
            savedEventId = saved.id
        }
    }

    fun complete(event: Event, completed: Boolean) = write("无法更新事件状态") {
        withContext(Dispatchers.IO) { store.setCompleted(event.id, completed) }
        events = events.map { if (it.id == event.id) it.copy(completed = completed) else it }
    }

    fun movePending(id: String, targetId: String) {
        val from = events.indexOfFirst { it.id == id && !it.completed }
        val to = events.indexOfFirst { it.id == targetId && !it.completed }
        if (from < 0 || to < 0 || from == to) return
        val reordered = events.toMutableList().apply { add(to, removeAt(from)) }
        write("无法保存待办顺序") {
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
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                android.util.Log.e("NextToDo", errorMessage, exception)
                onFailure()
                message = errorMessage
            } finally {
                busy = false
            }
        }
    }

    private fun newEvent(day: LocalDate = LocalDate.now()) = Event(id = UUID.randomUUID().toString(), day = day)
}
