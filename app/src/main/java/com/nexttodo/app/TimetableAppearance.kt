package com.nexttodo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

@Composable
internal fun TimetableAppearance(model: TimetableViewModel, aspectRatio: Float, onDismiss: () -> Unit) {
    AppearancePage("课表外观", !model.busy, onDismiss) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("课表背景 · 选择背景", style = MaterialTheme.typography.titleMedium)
            TimetableSkin.entries.filter { it != TimetableSkin.CUSTOM }.chunked(2).forEach { skins ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    skins.forEach { skin ->
                        SkinOption(skin, null, model.selected.skin == skin, !model.busy, Modifier.weight(1f)) {
                            model.changeSkin(skin)
                        }
                    }
                    if (skins.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SkinOption(skin: TimetableSkin, image: String?, selected: Boolean, enabled: Boolean,
    modifier: Modifier, label: String = skin.label, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).border(if (selected) 2.dp else 1.dp,
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable(enabled = enabled, onClick = onClick).padding(6.dp)) {
        Box(Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(10.dp))) {
            TimetableWallpaper(skin, image, Modifier.fillMaxSize())
            Row(Modifier.fillMaxSize().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                courseColors.take(3).forEachIndexed { index, color ->
                    Box(Modifier.weight(1f).padding(top = (index * 13).dp)
                        .height((58 - index * 9).dp).clip(RoundedCornerShape(4.dp))
                        .background(color.copy(alpha = .8f)))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (selected) Text("已选", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            if (onRemove != null) TextButton(onClick = onRemove, enabled = enabled,
                modifier = Modifier.height(32.dp).semantics { contentDescription = "移除$label" },
                contentPadding = PaddingValues(horizontal = 4.dp)) { Text("移除", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun AppearancePage(title: String, enabled: Boolean, onDismiss: () -> Unit,
    action: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (enabled) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val window = (view.parent as DialogWindowProvider).window
        val dark = timetableDark
        SideEffect {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TimetableAction(TableGlyph.CLOSE, "关闭$title", enabled, onDismiss)
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    action()
                }
                content()
            }
        }
    }
}
