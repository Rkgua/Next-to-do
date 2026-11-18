package com.nexttodo.app

import org.json.JSONArray
import org.json.JSONObject

internal data class TimetableStyle(
    val cardOpacity: Float = 1f,
    val cardCornerDp: Float = 5f,
    val cardGapDp: Float = 1.25f,
    val courseFontSp: Float = 11f,
    val periodHeightDp: Float = 64f,
    val courseTextColor: Int = -1,
    val cardColors: List<Int> = emptyList(),
    val sansSerif: Boolean = false,
    val headerBackgroundColor: Int? = null,
    val backgroundDim: Float? = null,
    val toolbarTextColor: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("cardOpacity", cardOpacity).put("cardCornerDp", cardCornerDp).put("cardGapDp", cardGapDp)
        .put("courseFontSp", courseFontSp).put("periodHeightDp", periodHeightDp)
        .put("courseTextColor", colorHex(courseTextColor))
        .put("cardColors", JSONArray(cardColors.map(::colorHex))).put("sansSerif", sansSerif)
        .put("headerBackgroundColor", headerBackgroundColor?.let(::colorHex) ?: JSONObject.NULL)
        .put("backgroundDim", backgroundDim ?: JSONObject.NULL)
        .put("toolbarTextColor", toolbarTextColor?.let(::colorHex) ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject?, current: TimetableStyle = TimetableStyle()): TimetableStyle {
            if (json == null) return current
            val fields = setOf("cardOpacity", "cardCornerDp", "cardGapDp", "courseFontSp", "periodHeightDp",
                "courseTextColor", "cardColors", "sansSerif", "headerBackgroundColor", "backgroundDim", "toolbarTextColor")
            require(json.keys().asSequence().all { it in fields }) { "AI 返回了不支持的外观属性，请重新生成" }
            fun number(key: String, fallback: Float, min: Float, max: Float): Float {
                if (!json.has(key)) return fallback
                val value = json.getDouble(key).toFloat()
                require(value.isFinite() && value in min..max) { "AI 返回的 $key 超出可用范围，请重新生成" }
                return value
            }
            fun color(key: String, fallback: Int): Int = if (json.has(key))
                requireNotNull(parseTextColor(json.getString(key))) { "AI 返回了无效的文字颜色" } else fallback
            val palette = if (json.has("cardColors")) json.getJSONArray("cardColors").let { array ->
                require(array.length() <= 12) { "最多支持 12 种课程颜色" }
                List(array.length()) { index ->
                    requireNotNull(parseTextColor(array.getString(index))) { "AI 返回了无效的课程颜色" }
                }
            } else current.cardColors
            return TimetableStyle(
                number("cardOpacity", current.cardOpacity, .25f, 1f),
                number("cardCornerDp", current.cardCornerDp, 0f, 24f),
                number("cardGapDp", current.cardGapDp, 0f, 4f),
                number("courseFontSp", current.courseFontSp, 8f, 18f),
                number("periodHeightDp", current.periodHeightDp, 48f, 110f),
                color("courseTextColor", current.courseTextColor), palette,
                if (json.has("sansSerif")) json.getBoolean("sansSerif") else current.sansSerif,
                if (!json.has("headerBackgroundColor")) current.headerBackgroundColor
                else if (json.isNull("headerBackgroundColor")) null else color("headerBackgroundColor", 0),
                if (!json.has("backgroundDim")) current.backgroundDim
                else if (json.isNull("backgroundDim")) null else number("backgroundDim", 0f, 0f, .7f),
                if (!json.has("toolbarTextColor")) current.toolbarTextColor
                else if (json.isNull("toolbarTextColor")) null else color("toolbarTextColor", 0),
            )
        }
    }
}

internal fun colorHex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

internal data class TimetableAiDraft(val original: Timetable, val preview: Timetable, val summary: String,
    val courseChanges: List<AiCourseChange> = emptyList())

@JvmOverloads
internal fun parseTimetableAiDraft(original: Timetable, json: JSONObject, dark: Boolean, allowCourseChanges: Boolean = false): TimetableAiDraft {
    val supported = setOf("summary", "style", "background", "headerTextColor", "timeTextColor", "unsupported", "courseChanges")
    require(json.keys().asSequence().all { it in supported }) { "AI 返回了不支持的修改，请重新生成" }
    val unsupported = if (json.isNull("unsupported")) "" else json.optString("unsupported").trim()
    require(unsupported.isBlank()) { unsupported.take(180) }
    val requested = json.optString("background", "KEEP")
    val skin = if (requested == "KEEP") original.skin else
        requireNotNull(TimetableSkin.entries.find { it.name == requested }) { "AI 选择了不可用的背景" }
    require(skin != TimetableSkin.CUSTOM || original.backgroundImage != null) { "请先导入自己的背景图片" }
    val key = backgroundKey(skin, original.backgroundImage)
    val currentText = original.backgroundTextColors[key] ?: defaultBackgroundText(skin, dark)
    fun textColor(name: String, fallback: Int): Int = if (json.has(name))
        requireNotNull(parseTextColor(json.getString(name))) { "AI 返回了无效的文字颜色" } else fallback
    val updatedText = BackgroundTextColors(textColor("headerTextColor", currentText.header), textColor("timeTextColor", currentText.time))
    val textColors = if (json.has("headerTextColor") || json.has("timeTextColor")) original.backgroundTextColors + (key to updatedText)
        else original.backgroundTextColors
    val operations = if (json.has("courseChanges")) json.getJSONArray("courseChanges") else JSONArray()
    require(allowCourseChanges || operations.length() == 0) { "请切换到课程编辑后，再生成课程修改" }
    val result = parseAiCourseChanges(original.courses, operations)
    val preview = original.copy(skin = skin,
        style = TimetableStyle.fromJson(if (json.has("style")) json.getJSONObject("style") else null, original.style),
        backgroundTextColors = textColors, courses = result.courses)
    require(preview != original) { "没有生成改动，请换一种描述再试" }
    return TimetableAiDraft(original, preview, json.optString("summary", "已生成课表修改方案").take(240), result.changes)
}
