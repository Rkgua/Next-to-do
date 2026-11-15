package com.nexttodo.app

import java.util.UUID
import java.time.LocalDate
import java.time.LocalDateTime

enum class EventType(val label: String, val fields: List<String> = emptyList()) {
    GENERAL("普通"),
    CLASS("上课", listOf("课程", "地点")),
    EXAM("考试", listOf("科目", "考场")),
    PARCEL("快递", listOf("快递公司", "快递单号", "取件码")),
}

enum class FieldType(val label: String) {
    TEXT("文本"), TIME("时间"), IMAGE("图片"), FILE("文件"),
}

data class Attachment(
    val uri: String,
    val name: String,
    val localId: String? = null,
)

data class EventField(
    val name: String = "",
    val type: FieldType = FieldType.TEXT,
    val value: String = "",
    val attachment: Attachment? = null,
    val presetFor: EventType? = null,
    val id: String = UUID.randomUUID().toString(),
)

data class Event(
    val id: String,
    val type: EventType = EventType.GENERAL,
    val title: String = "",
    val description: String = "",
    val time: String = "",
    val completed: Boolean = false,
    val originalImage: Attachment? = null,
    val fields: List<EventField> = emptyList(),
    val day: LocalDate? = null,
) {
    val calendarDay: LocalDate?
        get() = time.takeIf(String::isNotBlank)?.let { LocalDateTime.parse(it).toLocalDate() } ?: day

    fun withType(newType: EventType): Event {
        if (type == newType) return this
        val retained = fields.filter { it.presetFor == null || it.value.isNotBlank() }
        val presets = newType.fields.map { name ->
            retained.firstOrNull { it.name == name && it.presetFor == newType }
                ?: EventField(name = name, presetFor = newType)
        }
        val extraFields = retained.filter { it.presetFor != newType }
        return copy(type = newType, fields = presets + extraFields)
    }
}
