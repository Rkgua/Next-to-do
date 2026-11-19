package com.nexttodo.app

import android.content.Intent
import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private var timerRequests by mutableIntStateOf(0)
    private var eventRequest by mutableStateOf<Pair<Int, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val model = ViewModelProvider(this)[EventViewModel::class.java]
        val timetable = ViewModelProvider(this)[TimetableViewModel::class.java]
        val updates = ViewModelProvider(this)[AppUpdateViewModel::class.java]
        val todayAppearance = ViewModelProvider(this)[TodayAppearanceViewModel::class.java]
        val settings = AppSettings(applicationContext)
        handleNavigationIntent(intent)
        setContent {
            var theme by remember { mutableStateOf(settings.theme) }
            val dark = when (theme) {
                AppTheme.SYSTEM -> isSystemInDarkTheme()
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
            }
            MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
                NextToDo(model, timetable, todayAppearance, updates, settings, lifecycle, theme, dark, window, timerRequests, eventRequest) {
                    settings.theme = it
                    theme = it
                }
                UpdatePrompt(updates)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNavigationIntent(intent)
    }

    private fun handleNavigationIntent(intent: Intent) {
        if (intent.getBooleanExtra(PomodoroTimer.OPEN_POMODORO, false)) {
            timerRequests += 1
            intent.removeExtra(PomodoroTimer.OPEN_POMODORO)
        }
        intent.getStringExtra(EventReminders.OPEN_EVENT)?.let { id ->
            eventRequest = ((eventRequest?.first ?: 0) + 1) to id
            intent.removeExtra(EventReminders.OPEN_EVENT)
        }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF006BC4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7F2FF),
    onPrimaryContainer = Color(0xFF163B5D),
    secondary = Color(0xFF345D81),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEEEFF),
    onSecondaryContainer = Color(0xFF183E60),
    background = Color(0xFFF5F8FC),
    surface = Color.White,
    onBackground = Color(0xFF182B3D),
    onSurface = Color(0xFF182B3D),
    surfaceVariant = Color(0xFFE8EFF7),
    onSurfaceVariant = Color(0xFF475F75),
    outline = Color(0xFF71869A),
    outlineVariant = Color(0xFFC9D9E8),
    surfaceContainerHigh = Color(0xFFE9F1FA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6E9BFA),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF303034),
    onPrimaryContainer = Color(0xFFE5E5E9),
    secondary = Color(0xFF8DABCA),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF303034),
    onSecondaryContainer = Color(0xFFE5E5E9),
    background = Color.Black,
    surface = Color(0xFF1C1C1E),
    onBackground = Color(0xFFE8E8EC),
    onSurface = Color(0xFFE8E8EC),
    surfaceVariant = Color(0xFF29292D),
    onSurfaceVariant = Color(0xFFB8B8BF),
    outline = Color(0xFF818188),
    outlineVariant = Color(0xFF3D3D43),
    surfaceTint = Color.Transparent,
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF353539),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF141416),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF252528),
    surfaceContainerHighest = Color(0xFF303034),
)

private enum class Tab(val label: String, val title: String, val icon: Int) {
    DAILY("Today", "每日提醒", R.drawable.tab_today),
    SCHEDULE("Timetable", "课表速看", R.drawable.tab_timetable),
    ADD("Add", "添加事件", R.drawable.tab_add),
    PROFILE("My", "我的", R.drawable.tab_my),
}

@Composable
private fun NextToDo(
    model: EventViewModel,
    timetable: TimetableViewModel,
    todayAppearance: TodayAppearanceViewModel,
    updates: AppUpdateViewModel,
    settings: AppSettings,
    lifecycle: Lifecycle,
    theme: AppTheme,
    dark: Boolean,
    window: Window,
    timerRequests: Int,
    eventRequest: Pair<Int, String>?,
    onThemeChange: (AppTheme) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.DAILY) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var showingPomodoro by rememberSaveable { mutableStateOf(false) }
    var timetableTabsVisible by rememberSaveable { mutableStateOf(true) }
    var todayFocus by rememberSaveable { mutableStateOf(false) }
    var courseJump by remember { mutableIntStateOf(0) }
    LaunchedEffect(tab) { timetableTabsVisible = true }
    var selectedDate by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val selectedDay = LocalDate.parse(selectedDate)
    val detail = model.events.firstOrNull { it.id == detailId }?.let { it.onDate(selectedDay) ?: it }
    val showingToday = tab == Tab.DAILY && detail == null
    val showingTimetable = tab == Tab.SCHEDULE
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    SideEffect {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = when {
                showingToday -> false
                showingTimetable -> timetable.selected.style.headerBackgroundColor?.let { Color(it).luminance() > .5f } ?: !dark
                else -> !dark
            }
            isAppearanceLightNavigationBars = !dark
        }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model.message) {
        val message = model.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        model.dismissMessage()
    }
    LaunchedEffect(model.savedEventId) {
        val savedId = model.savedEventId ?: return@LaunchedEffect
        selectedDate = (model.events.firstOrNull { it.id == savedId }?.calendarDay ?: LocalDate.now()).toString()
        detailId = null
        tab = Tab.DAILY
        model.acknowledgeSave()
    }
    LaunchedEffect(timerRequests) {
        if (timerRequests > 0) {
            detailId = null
            showingPomodoro = true
            tab = Tab.PROFILE
        }
    }
    LaunchedEffect(eventRequest) {
        eventRequest?.let { (_, id) ->
            detailId = id
            showingPomodoro = false
            tab = Tab.DAILY
        }
    }
    BackHandler(tab != Tab.DAILY || detailId != null) {
        if (tab == Tab.PROFILE && showingPomodoro) {
            showingPomodoro = false
        } else {
            detailId = null
            tab = Tab.DAILY
        }
    }

    Scaffold(
        modifier = Modifier.background(
            when {
                showingToday -> todayHeaderBackground
                showingTimetable -> timetable.selected.style.headerBackgroundColor?.let(::Color) ?: timetableTop
                else -> MaterialTheme.colorScheme.background
            }
        )
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!keyboardVisible && !showingTimetable) {
                BottomTabs(tab, dark) {
                    detailId = null
                    showingPomodoro = false
                    tab = it
                }
            }
        },
    ) { padding ->
        if (showingToday) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                TodayScreen(
                    selectedDay = selectedDay,
                    onDayChange = { selectedDate = it.toString() },
                    model = model,
                    onOpen = { detailId = it.id; it.occurrenceDate?.let { day -> selectedDate = day.toString() } },
                    onAdd = {
                        model.clearDraft(selectedDay)
                        tab = Tab.ADD
                    },
                    onEnterFocus = { todayFocus = true },
                )
                if (todayFocus) TodayFocusScreen(selectedDay, model, timetable, todayAppearance,
                    onDismiss = { todayFocus = false },
                    onOpen = { todayFocus = false; detailId = it.id },
                    onAdd = { todayFocus = false; model.clearDraft(selectedDay); tab = Tab.ADD })
            }
            return@Scaffold
        }
        if (showingTimetable) {
            // The wallpaper and course viewport keep a fixed size. Only the overlaid bar moves.
            Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).clipToBounds()) {
                TimetableScreen(timetable,
                    bottomContentPadding = 68.dp + WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding(),
                    onHideNavigation = { timetableTabsVisible = false },
                    onToggleNavigation = { timetableTabsVisible = !timetableTabsVisible })
                AnimatedVisibility(visible = !keyboardVisible && timetableTabsVisible,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(180)),
                    exit = slideOutVertically(tween(190, easing = FastOutLinearInEasing)) { it } + fadeOut(tween(130))) {
                    BottomTabs(tab, dark) {
                        detailId = null
                        showingPomodoro = false
                        tab = it
                    }
                }
            }
            return@Scaffold
        }
        if (tab == Tab.DAILY && detail != null) {
            EventDetails(
                event = detail,
                editable = !model.busy,
                modifier = Modifier.fillMaxSize().padding(padding),
                onBack = { detailId = null },
                onEdit = {
                    model.edit(detail)
                    detailId = null
                    tab = Tab.ADD
                },
            )
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            Text(
                when {
                    tab == Tab.ADD && model.events.any { it.id == model.draft.id } -> "编辑事件"
                    tab == Tab.PROFILE && showingPomodoro -> "番茄钟"
                    else -> tab.title
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            if (model.loadingFailed) {
                Button(onClick = model::reload, enabled = !model.busy) { Text("重新读取事件") }
                return@Column
            }
            when (tab) {
                Tab.DAILY, Tab.SCHEDULE -> Unit
                Tab.ADD -> EventEditor(
                    model.draft,
                    enabled = !model.busy,
                    recognizing = model.recognizing,
                    onChange = model::updateDraft,
                    onRecognize = model::recognizeImage,
                    onSave = model::save,
                    onReset = { model.clearDraft(selectedDay) },
                )
                Tab.PROFILE -> if (showingPomodoro) {
                    PomodoroScreen(lifecycle) { showingPomodoro = false }
                } else {
                    ProfileSettings(settings, lifecycle, theme, updates, onThemeChange,
                        onOpenPomodoro = { showingPomodoro = true }, todayAppearance = todayAppearance, timetable = timetable)
                }
            }
        }
    }
}

@Composable
private fun BottomTabs(selected: Tab, dark: Boolean, onSelect: (Tab) -> Unit) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = if (dark) Color(0xFFADB9BF) else Color(0xFF707070)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .selectableGroup()
                .padding(horizontal = 8.dp)
        ) {
            Tab.entries.forEach { tab ->
                val active = selected == tab
                Box(
                    Modifier.weight(1f).height(68.dp)
                        .selectable(
                            selected = active,
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = { onSelect(tab) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val contentColor = if (active) activeColor else inactiveColor
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(tab.icon),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = contentColor,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tab.label,
                            color = contentColor,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}
