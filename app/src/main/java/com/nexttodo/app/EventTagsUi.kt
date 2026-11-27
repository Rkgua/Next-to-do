package com.nexttodo.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val tagRed = Color(0xFFC63B3B)
private const val defaultCustomTagColor = 0xFF006BC4.toInt()
private fun tagInk(color: Color): Color = if (color.luminance() > .179f) Color.Black else Color.White
private fun EventTagKind.tagColor(): Color = when (this) {
    EventTagKind.URGENT -> tagRed
    EventTagKind.IMPORTANT -> Color(0xFFBD5700)
    EventTagKind.NORMAL -> Color(0xFF006BC4)
    EventTagKind.OPTIONAL -> Color(0xFF68717D)
    EventTagKind.CUSTOM -> tagRed
}

@Composable
internal fun EventTagEditor(tags: List<EventTag>, enabled: Boolean, onChange: (List<EventTag>) -> Unit) {
    var addingCustom by rememberSaveable { mutableStateOf(false) }
    var customName by rememberSaveable { mutableStateOf("") }
    var customHex by rememberSaveable { mutableStateOf(colorHex(defaultCustomTagColor)) }
    var textHex by rememberSaveable { mutableStateOf("#FFFFFF") }
    var automaticTextColor by rememberSaveable { mutableStateOf(true) }
    var editingTextColor by rememberSaveable { mutableStateOf(false) }
    var editingName by rememberSaveable { mutableStateOf<String?>(null) }
    Text("可多选，再点一次取消", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        EventTagKind.entries.filter { it != EventTagKind.CUSTOM }.forEach { kind ->
            val selected = tags.any { it.kind == kind }
            FilterChip(selected = selected, enabled = enabled,
                onClick = { onChange(if (selected) tags.filterNot { it.kind == kind } else tags + EventTag(kind)) },
                label = { Text(kind.label) },
                leadingIcon = if (selected) ({ Text("✓", color = Color.White, fontWeight = FontWeight.Bold) }) else null,
                shape = RoundedCornerShape(12.dp), border = BorderStroke(0.dp, Color.Transparent),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = kind.tagColor(), labelColor = Color.White,
                    selectedContainerColor = kind.tagColor(), selectedLabelColor = Color.White,
                    selectedLeadingIconColor = Color.White),
                modifier = Modifier.testTag("event-tag-${kind.name}"))
        }
        OutlinedButton(onClick = {
            customName = ""; customHex = colorHex(defaultCustomTagColor); editingName = null
            textHex = "#FFFFFF"; automaticTextColor = true; editingTextColor = false; addingCustom = true
        }, enabled = enabled,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            shape = RoundedCornerShape(12.dp), modifier = Modifier.testTag("event-tag-custom-add")) {
            Text("!", color = tagRed, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(6.dp))
            Text("自定义", color = MaterialTheme.colorScheme.onSurface)
        }
    }
    val customTags = tags.filter { it.kind == EventTagKind.CUSTOM }
    if (customTags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        customTags.forEach { tag ->
            val background = tag.color?.let(::Color) ?: MaterialTheme.colorScheme.surfaceVariant
            val foreground = tag.textColor?.let(::Color)
                ?: if (tag.color == null) MaterialTheme.colorScheme.onSurface else tagInk(background)
            InputChip(selected = true, enabled = enabled,
                onClick = {
                    customName = tag.name; customHex = colorHex(tag.color ?: defaultCustomTagColor)
                    textHex = colorHex(tag.textColor ?: tagInk(Color(tag.color ?: defaultCustomTagColor)).toArgb())
                    automaticTextColor = tag.textColor == null; editingTextColor = false
                    editingName = tag.name; addingCustom = true
                },
                label = { Text(tag.name) },
                trailingIcon = {
                    IconButton(enabled = enabled, onClick = { onChange(tags - tag) }, modifier = Modifier.size(28.dp)
                        .semantics { contentDescription = "移除标签 · ${tag.name}" }) { Text("×", color = foreground) }
                },
                colors = InputChipDefaults.inputChipColors(selectedContainerColor = background,
                    selectedLabelColor = foreground),
                modifier = Modifier.semantics { contentDescription = "编辑标签 · ${tag.name}" })
        }
    }
    val parsedColor = parseTextColor(customHex)
    val effectiveTextHex = if (automaticTextColor) colorHex(tagInk(Color(parsedColor ?: defaultCustomTagColor)).toArgb()) else textHex
    val parsedTextColor = parseTextColor(effectiveTextHex)
    if (addingCustom) AlertDialog(onDismissRequest = { addingCustom = false },
        title = { Text("自定义标签") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = customName, onValueChange = { customName = it.take(MAX_EVENT_TAG_LENGTH) },
                    label = { Text("标签名称") }, singleLine = true,
                    supportingText = { Text("${customName.length}/$MAX_EVENT_TAG_LENGTH") },
                    modifier = Modifier.fillMaxWidth().testTag("event-tag-custom-name"))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("标签底色", "文字颜色").forEachIndexed { index, label ->
                        SegmentedButton(selected = editingTextColor == (index == 1), onClick = { editingTextColor = index == 1 },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            modifier = Modifier.testTag(if (index == 1) "event-tag-color-mode-text" else "event-tag-color-mode-background")) { Text(label) }
                    }
                }
                if (editingTextColor) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("自动适配底色", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = automaticTextColor, onCheckedChange = {
                        if (!it) textHex = effectiveTextHex
                        automaticTextColor = it
                    }, modifier = Modifier.testTag("event-tag-auto-text-color"))
                }
                val palette = if (editingTextColor) listOf("白色" to 0xFFFFFFFF, "黑色" to 0xFF000000,
                    "红色" to 0xFFC63B3B, "黄色" to 0xFFE7B63D, "绿色" to 0xFF2E7D32, "蓝色" to 0xFF006BC4,
                    "紫色" to 0xFF7E57C2, "灰色" to 0xFF68717D)
                    else listOf("红色" to 0xFFC63B3B, "橙色" to 0xFFBD5700, "黄色" to 0xFFE7B63D, "绿色" to 0xFF2E7D32,
                        "青色" to 0xFF00897B, "蓝色" to 0xFF006BC4, "紫色" to 0xFF7E57C2, "灰色" to 0xFF68717D)
                palette.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceBetween) {
                        row.forEach { (label, value) ->
                            val selected = (if (editingTextColor) parsedTextColor else parsedColor) == value.toInt()
                            Box(Modifier.size(44.dp).selectable(selected, role = Role.RadioButton,
                                onClick = {
                                    if (editingTextColor) { textHex = colorHex(value.toInt()); automaticTextColor = false }
                                    else customHex = colorHex(value.toInt())
                                })
                                .semantics { contentDescription = "${if (editingTextColor) "文字颜色" else "标签颜色"} · $label" }, contentAlignment = Alignment.Center) {
                                Surface(shape = CircleShape, color = Color(value), modifier = Modifier.size(32.dp),
                                    border = BorderStroke(if (selected) 2.dp else 1.dp,
                                        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant)) {
                                    Box(contentAlignment = Alignment.Center) { if (selected) Text("✓", color = tagInk(Color(value))) }
                                }
                            }
                        }
                    }
                }
                if (editingTextColor) OutlinedTextField(value = effectiveTextHex, onValueChange = {
                    textHex = it.take(7); automaticTextColor = false
                }, label = { Text("文字颜色（#RRGGBB）") }, singleLine = true, isError = parsedTextColor == null,
                    supportingText = { if (parsedTextColor == null) Text("请输入六位颜色值，例如 #FFFFFF") },
                    modifier = Modifier.fillMaxWidth().testTag("event-tag-custom-text-color"))
                else OutlinedTextField(value = customHex, onValueChange = { customHex = it.take(7) },
                    label = { Text("自定义颜色（#RRGGBB）") }, singleLine = true, isError = parsedColor == null,
                    supportingText = { if (parsedColor == null) Text("请输入六位颜色值，例如 #006BC4") },
                    modifier = Modifier.fillMaxWidth().testTag("event-tag-custom-color"))
                Text("预览", style = MaterialTheme.typography.labelLarge)
                EventTagBadges(listOf(EventTag(EventTagKind.CUSTOM, customName.trim().ifBlank { "标签名称" },
                    parsedColor ?: defaultCustomTagColor, if (automaticTextColor) null else parsedTextColor)))
            }
        },
        confirmButton = { TextButton(enabled = customName.isNotBlank() && parsedColor != null && parsedTextColor != null, onClick = {
            val tag = EventTag(EventTagKind.CUSTOM, customName.trim(), parsedColor, if (automaticTextColor) null else parsedTextColor)
            val updated = tags.filterNot { it.kind == EventTagKind.CUSTOM && (it.name == editingName || it.name == tag.name) } + tag
            onChange(updated.normalizedTags())
            addingCustom = false
        }) { Text(if (editingName == null) "添加" else "保存") } },
        dismissButton = { TextButton(onClick = { addingCustom = false }) { Text("取消") } })
}

@Composable
internal fun EventTagBadges(tags: List<EventTag>, modifier: Modifier = Modifier, alignEnd: Boolean = false) {
    if (tags.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp, if (alignEnd) Alignment.End else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        tags.forEach { tag ->
            val custom = tag.kind == EventTagKind.CUSTOM
            val background = if (custom) tag.color?.let(::Color) ?: MaterialTheme.colorScheme.surfaceVariant else tag.kind.tagColor()
            Surface(shape = RoundedCornerShape(6.dp),
                color = background,
                contentColor = if (custom) tag.textColor?.let(::Color)
                    ?: if (tag.color == null) MaterialTheme.colorScheme.onSurface else tagInk(background) else tagInk(background)) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tag.name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
