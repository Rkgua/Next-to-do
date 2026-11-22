package com.nexttodo.app

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val SceneWhite = Color(0xFFF8F5ED)

@Composable
internal fun ProfileMyScreen(
    settings: AppSettings,
    lifecycle: Lifecycle,
    theme: AppTheme,
    updates: AppUpdateViewModel,
    onThemeChange: (AppTheme) -> Unit,
    todayAppearance: TodayAppearanceViewModel,
    timetable: TimetableViewModel,
    daily: ProfileDailyViewModel,
    timerExpanded: Boolean,
    onTimerExpanded: (Boolean) -> Unit,
    hostActivity: android.app.Activity,
) {
    var stage by rememberSaveable { mutableIntStateOf(0) }
    var showFocusHub by rememberSaveable { mutableStateOf(false) }
    var profileOrigin by remember { mutableStateOf(Offset.Zero) }
    var bubbleOrigin by remember { mutableStateOf<Offset?>(null) }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val settingsScroll = rememberScrollState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun changeStage(value: Int) { focus.clearFocus(); keyboard?.hide(); stage = value }
    LaunchedEffect(timerExpanded) { if (timerExpanded) { focus.clearFocus(); keyboard?.hide() } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { owner, event ->
            active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (event == Lifecycle.Event.ON_RESUME) daily.refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(active) {
        if (active) while (true) { daily.refresh(); delay(60_000) }
    }
    BackHandler(stage > 0 && !timerExpanded && !showSources) { changeStage(0) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().onGloballyPositioned { profileOrigin = it.positionInRoot() }) {
        val density = LocalDensity.current
        val maximum = maxHeight.coerceAtLeast(1.dp)
        val minimum = minOf(140.dp, maximum * .34f)
        val middle = maxOf(minimum, maximum * .6f)
        val anchors = with(density) { listOf(minimum.toPx(), middle.toPx(), maximum.toPx()) }
        val settled by animateDpAsState(listOf(minimum, middle, maximum)[stage], tween(380), label = "profile curtain")
        var dragHeight by remember { mutableStateOf<Float?>(null) }
        val exposed = with(density) { dragHeight?.toDp() } ?: settled
        val full = ((exposed - minimum).value / (maximum - minimum).value.coerceAtLeast(1f)).coerceIn(0f, 1f)
        fun finishDrag(velocity: Float) {
            stage = settleDailyScene(dragHeight ?: with(density) { settled.toPx() }, anchors, -velocity)
            dragHeight = null
        }
        val drag = rememberDraggableState { delta ->
            dragHeight = ((dragHeight ?: with(density) { settled.toPx() }) + delta).coerceIn(anchors[0], anchors[2])
        }
        val scrollConnection = remember(anchors, settingsScroll) {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                override fun onPreScroll(available: androidx.compose.ui.geometry.Offset,
                    source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) return androidx.compose.ui.geometry.Offset.Zero
                    val before = dragHeight ?: with(density) { settled.toPx() }
                    if ((available.y > 0 && settingsScroll.value == 0) || (available.y < 0 && before > anchors[0] + .5f)) {
                        val after = (before + available.y).coerceIn(anchors[0], anchors[2])
                        if (after != before) {
                            if (dragHeight == null) { focus.clearFocus(); keyboard?.hide() }
                            dragHeight = after
                        }
                        return androidx.compose.ui.geometry.Offset(0f, after - before)
                    }
                    return androidx.compose.ui.geometry.Offset.Zero
                }
                override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    if (dragHeight == null) return androidx.compose.ui.unit.Velocity.Zero
                    finishDrag(available.y)
                    return available
                }
            }
        }
        val sceneSemantics = Modifier.semantics {
            contentDescription = "我的页面，下拉展开背景，上滑返回设置"
            stateDescription = listOf("风景部分露出", "风景半展开", "风景全部展开")[stage]
            customActions = listOf(
                CustomAccessibilityAction("半展开风景") { changeStage(1); true },
                CustomAccessibilityAction("全部展开风景") { changeStage(2); true },
                CustomAccessibilityAction("返回设置") { changeStage(0); true })
        }
        Column(Modifier.fillMaxWidth().height(exposed).clipToBounds().then(sceneSemantics).nestedScroll(scrollConnection)
            .draggable(drag, Orientation.Vertical,
                onDragStarted = { focus.clearFocus(); keyboard?.hide(); dragHeight = with(density) { settled.toPx() } },
                onDragStopped = { finishDrag(it) })) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("我的", color = SceneWhite, fontSize = 32.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.onGloballyPositioned {
                            val bounds = it.boundsInRoot()
                            bubbleOrigin = Offset(bounds.right - profileOrigin.x + with(density) { 10.dp.toPx() },
                                bounds.center.y - profileOrigin.y - with(density) { 28.dp.toPx() })
                        })
                    Text(LocalDate.now(dailyZone).format(DateTimeFormatter.ofPattern("MM.dd  EEEE", Locale.CHINA)),
                        Modifier.padding(top = 8.dp), color = SceneWhite.copy(alpha = .75f), fontSize = 12.sp)
                }
                TextButton(onClick = { showSources = true }) { Text("来源", color = SceneWhite.copy(alpha = .85f), fontSize = 12.sp) }
            }
            if (exposed < minimum + 72.dp) {
                Spacer(Modifier.weight(1f))
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 28.dp, end = 64.dp, top = (12 + 35 * full).dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.Center) {
                    if (daily.content.quote != null) Text("“", fontFamily = FontFamily.Serif,
                        color = SceneWhite.copy(alpha = .52f), fontSize = 42.sp, lineHeight = 34.sp)
                    SelectionContainer {
                        Text(daily.content.quote?.text ?: if (daily.loading) "正在遇见今天的一句话…" else "每日一句暂未加载，轻点下方刷新。",
                            fontFamily = FontFamily.Serif, fontSize = (22 + 5 * full).sp,
                            lineHeight = (34 + 8 * full).sp, letterSpacing = 1.sp,
                            color = SceneWhite, fontWeight = FontWeight.Light)
                    }
                    val quote = daily.content.quote
                    val attribution = quote?.attribution?.ifBlank { quote.source }
                    if (attribution != null) Text("—  $attribution", Modifier.padding(top = 20.dp),
                        color = SceneWhite.copy(alpha = .72f), fontSize = 11.sp, lineHeight = 18.sp)
                    if (quote?.date != null && quote.date != dailyDate()) Text("上次留存 · ${quote.date}",
                        Modifier.padding(top = 8.dp), color = SceneWhite.copy(alpha = .6f), fontSize = 10.sp)
                    quote?.sourceUrl?.let { link ->
                        val context = LocalContext.current
                        Text("${quote.source} ↗", Modifier.padding(top = 10.dp).clickable { openDailySource(context, link) },
                            color = SceneWhite.copy(alpha = .68f), fontSize = 10.sp)
                    }
                }
                DailySceneFooter(daily, full)
            }
        }
        // Settings flow directly from the photo header; only the individual setting groups are cards.
        val transition = (12 * (1 - full)).dp
        val bodyTop = exposed - transition
        Box(Modifier.fillMaxWidth().offset { androidx.compose.ui.unit.IntOffset(0, with(density) { bodyTop.roundToPx() }) }
            .height((maxHeight - bodyTop).coerceAtLeast(0.dp)).nestedScroll(scrollConnection)
            .background(Brush.verticalGradient(listOf(Color.Transparent, profileSettingsBackground()),
                endY = with(density) { transition.toPx() }.coerceAtLeast(1f)))) {
            ProfileSettings(settings, lifecycle, theme, updates, onThemeChange,
                todayAppearance = todayAppearance, timetable = timetable,
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), bottomPadding = 12.dp,
                scrollState = settingsScroll)
        }
        if (daily.loading) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp),
            color = SceneWhite.copy(alpha = .7f), trackColor = Color.Transparent)
        FloatingPomodoro(lifecycle, timerExpanded, onTimerExpanded, bubbleOrigin) {
            onTimerExpanded(false)
            showFocusHub = true
        }
    }
    if (showSources) DailySourcesDialog(daily.sources, daily.loading, onDismiss = { showSources = false }) {
        daily.updateSources(it)
        showSources = false
    }
    if (showFocusHub) androidx.compose.ui.window.Dialog(onDismissRequest = { showFocusHub = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PomodoroHub(lifecycle, hostActivity) { showFocusHub = false }
    }
}

@Composable
private fun DailySceneFooter(daily: ProfileDailyViewModel, full: Float) {
    val context = LocalContext.current
    val image = daily.content.image.takeIf { daily.bitmap != null }
    Column(Modifier.fillMaxWidth().padding(start = 26.dp, end = 18.dp, bottom = (18 + 12 * full).dp)) {
        HorizontalDivider(color = SceneWhite.copy(alpha = .18f))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(if (image == null) "本地备用背景" else image.title.ifBlank { "必应每日一图" },
                    color = SceneWhite.copy(alpha = .9f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (image != null) {
                    Text("必应 · ${image.date}", Modifier.padding(top = 3.dp), color = SceneWhite.copy(alpha = .55f), fontSize = 9.sp)
                    Text(image.copyright, Modifier.padding(top = 4.dp).then(if (image.copyrightUrl != null)
                        Modifier.clickable { openDailySource(context, image.copyrightUrl) } else Modifier),
                        color = SceneWhite.copy(alpha = .62f), fontSize = 9.sp, lineHeight = 13.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            TextButton(onClick = { daily.refresh(force = true) }, enabled = !daily.loading) {
                Text(if (daily.loading) "更新中" else "刷新", color = SceneWhite.copy(alpha = .85f), fontSize = 11.sp)
            }
        }
        if (daily.notice.isNotBlank()) Text(daily.notice, Modifier.padding(top = 6.dp),
            color = SceneWhite.copy(alpha = .64f), fontSize = 10.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun DailySourcesDialog(sources: DailySources, loading: Boolean, onDismiss: () -> Unit, onSave: (DailySources) -> Unit) {
    var preferOwn by rememberSaveable { mutableStateOf(sources.preferOwnApi) }
    var own by rememberSaveable { mutableStateOf(sources.dailyApi) }
    var image by rememberSaveable { mutableStateOf(sources.imageApi) }
    var quote by rememberSaveable { mutableStateOf(sources.quoteApi) }
    val valid = listOf(own.trim(), image.trim(), quote.trim()).all(::isDailyHttpsUrl)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("每日内容来源") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("优先我的 API", Modifier.weight(1f))
                Switch(preferOwn, { preferOwn = it })
            }
            Text("连接失败时，图片与句子分别尝试公共接口。当天的句子会保留到次日。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SourceField("我的聚合接口", own) { own = it }
            SourceField("图片接口 · 必应格式", image) { image = it }
            SourceField("句子接口 · 一言格式", quote) { quote = it }
            if (!valid) Text("请填写有效的 HTTPS 地址", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { own = DEFAULT_DAILY_API; image = DEFAULT_BING_API; quote = DEFAULT_QUOTE_API; preferOwn = true }) {
                Text("恢复默认来源")
            }
        }
    }, confirmButton = {
        TextButton(onClick = { onSave(DailySources(preferOwn, own.trim(), image.trim(), quote.trim())) }, enabled = valid && !loading) { Text("保存") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun SourceField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) },
        maxLines = 3, isError = !isDailyHttpsUrl(value.trim()), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri))
}

private fun openDailySource(context: android.content.Context, link: String) {
    if (!isDailyHttpsUrl(link)) return
    try { context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri())) }
    catch (_: ActivityNotFoundException) { android.widget.Toast.makeText(context, "暂时没有可打开链接的应用", android.widget.Toast.LENGTH_SHORT).show() }
}
