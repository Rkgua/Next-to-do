package com.nexttodo.app

internal const val MAX_EVENT_TAG_LENGTH = 20

enum class EventTagKind(val label: String) {
    URGENT("紧急"), IMPORTANT("重要"), NORMAL("一般"), OPTIONAL("可选"), CUSTOM("自定义");
}

data class EventTag(val kind: EventTagKind, val name: String = kind.label, val color: Int? = null, val textColor: Int? = null)

internal fun List<EventTag>.normalizedTags(): List<EventTag> = mapNotNull { tag ->
    if (tag.kind != EventTagKind.CUSTOM) tag.copy(name = tag.kind.label, color = null, textColor = null)
    else tag.name.trim().take(MAX_EVENT_TAG_LENGTH).takeIf { it.isNotEmpty() }?.let {
        tag.copy(name = it, color = tag.color?.let { color -> color or 0xFF000000.toInt() },
            textColor = tag.textColor?.let { color -> color or 0xFF000000.toInt() })
    }
}.distinctBy { it.kind to it.name }
