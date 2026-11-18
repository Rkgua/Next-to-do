package com.nexttodo.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal fun generateTimetableAppearance(table: Timetable, prompt: String, key: String, dark: Boolean): TimetableAiDraft {
    val text = table.backgroundTextColors[backgroundKey(table.skin, table.backgroundImage)] ?: defaultBackgroundText(table.skin, dark)
    val current = JSONObject().put("style", table.style.toJson()).put("background", table.skin.name)
        .put("headerTextColor", colorHex(text.header)).put("timeTextColor", colorHex(text.time))
        .put("customBackgroundAvailable", table.backgroundImage != null).put("darkMode", dark)
    val system = """
        你是课表外观设计助手。根据用户要求修改当前外观，只输出 JSON 对象，不修改课程或日期。
        输入包含当前外观和修改要求。只返回需要修改的字段，以及简短中文 summary。
        可用字段：
        style: {cardOpacity:0.25到1,cardCornerDp:0到24,cardGapDp:0到4,courseFontSp:8到18,
        periodHeightDp:48到110,courseTextColor:"#RRGGBB",cardColors:["#RRGGBB"],sansSerif:true或false,
        headerBackgroundColor:"#RRGGBB"或null,toolbarTextColor:"#RRGGBB"或null,backgroundDim:0到0.7或null}。
        cardColors 最多12色，空数组恢复原课程颜色；sansSerif=false为衬线字体，true为无衬线。
        headerBackgroundColor=null使用应用主题；backgroundDim=null使用浅色原图、夜间遮罩的默认规则。
        toolbarTextColor是顶部周数、日期及按钮颜色，null时自动与头部背景适配。
        background 只能是 KEEP、DEFAULT、AURORA、STARS、STARS_2、SWIRL、CUSTOM。
        KEEP保留背景，CUSTOM仅在customBackgroundAvailable=true时可用。
        headerTextColor 和 timeTextColor 均为不透明 #RRGGBB；头部颜色指星期日期文字，time指左侧节次时间。
        课程文字和卡片背景保持可读的对比，清楚、克制地配色。
        如果用户要求超出这些外观设置，用 unsupported 字段说明可支持的调整，不要声称已完成不支持的功能。
        例：{"summary":"使用浅蓝课程卡片，增大圆角","style":{"cardColors":["#83ADEF","#70BFC8"],"cardCornerDp":10}}
    """.trimIndent()
    val payload = JSONObject().put("model", "deepseek-flash")
        .put("response_format", JSONObject().put("type", "json_object"))
        .put("thinking", JSONObject().put("type", "disabled")).put("max_tokens", 1400)
        .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", "当前外观：$current\n修改要求：$prompt")))
        .toString().toByteArray(Charsets.UTF_8)
    val connection = URL("https://api.deepseek.com/chat/completions").openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(payload.size)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $key")
        connection.outputStream.use { it.write(payload) }
        when (connection.responseCode) {
            200 -> Unit
            401, 403 -> throw IllegalArgumentException("DeepSeek 密钥不可用，请在 My 检查密钥")
            402 -> throw IllegalArgumentException("DeepSeek 账户余额不足")
            429 -> throw IllegalArgumentException("请求过于频繁，请稍后再试")
            else -> throw IOException("AI 暂时无法生成外观，请稍后重试")
        }
        val response = connection.inputStream.use { stream ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= 256 * 1024) { "AI 返回的内容过长，请重新生成" }
                bytes.write(buffer, 0, count)
            }
            bytes.toString("UTF-8")
        }
        val content = JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        return parseTimetableAiDraft(table, JSONObject(content), dark)
    } finally { connection.disconnect() }
}

@Composable
internal fun TimetableAiEditor(model: TimetableViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = timetableDark
    var prompt by rememberSaveable { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val draft = model.aiDraft
    var previewing by rememberSaveable { mutableStateOf(draft != null) }
    LaunchedEffect(draft) { if (draft != null) previewing = true }
    val busy = generating || model.busy
    AppearancePage(if (previewing && draft != null) "课表预览" else "AI 编辑", !model.busy, onDismiss,
        action = {
            if (previewing && draft != null) TextButton(onClick = { previewing = false }, enabled = !busy) { Text("返回修改") }
        }) {
        val proposal = draft
        if (previewing && proposal != null) {
            Text(proposal.summary, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f).fillMaxWidth()) { TimetableScreen(model, proposal.preview, isPreview = true) }
            if (model.message.isNotEmpty()) Text(model.message, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { model.applyAiDraft(proposal, onDismiss) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(if (model.busy) "保存中…" else "确认应用")
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("告诉 AI 你想怎样修改课表", style = MaterialTheme.typography.titleMedium)
                Text("支持调整背景、文字颜色、字号、课程卡片配色、透明度、圆角、间距和节次高度。")
                OutlinedTextField(prompt, { prompt = it.take(2000); error = "" },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy, minLines = 5, maxLines = 10,
                    label = { Text("修改要求") }, placeholder = { Text("例如：卡片换成浅蓝和薄荷色，圆角大一点，文字放大，时间用白色") })
                Text("使用 My 中的 DeepSeek 密钥。只发送修改要求和外观设置，课程内容保留在本机。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
                Button(enabled = prompt.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                    val original = model.selected
                    val base = draft?.takeIf { it.original == original }?.preview ?: original
                    val request = prompt.trim()
                    generating = true
                    error = ""
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                val key = AppSettings(context).readDeepSeekKey()
                                require(!key.isNullOrBlank()) { "请先在 My 中填写并保存 DeepSeek API 密钥" }
                                generateTimetableAppearance(base, request, key, dark).copy(original = original)
                            }
                            model.setAiPreview(result)
                            previewing = true
                        } catch (exception: CancellationException) { throw exception
                        } catch (exception: IllegalArgumentException) { error = exception.message ?: "无法生成外观，请重新描述要求"
                        } catch (_: org.json.JSONException) { error = "AI 返回的外观格式有误，请重新生成"
                        } catch (_: Exception) { error = "生成失败，请检查网络后重试" }
                        finally { generating = false }
                    }
                }) { Text(if (generating) "正在生成…" else "生成预览") }
                if (draft != null) OutlinedButton(enabled = !busy, onClick = { previewing = true },
                    modifier = Modifier.fillMaxWidth()) { Text("查看上次预览") }
                if (model.selected.style != TimetableStyle()) TextButton(enabled = !busy, onClick = {
                    val original = model.selected
                    model.setAiPreview(TimetableAiDraft(original, original.copy(style = TimetableStyle()),
                        "恢复默认界面样式，保留所选背景和背景文字配色"))
                }) { Text("预览恢复默认样式") }
                if (generating) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                Text("预览可以滑动查看。只有点击“确认应用”后才会保存。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
