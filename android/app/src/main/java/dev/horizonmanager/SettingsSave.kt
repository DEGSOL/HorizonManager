package dev.horizonmanager

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.zIndex

private class SaveAction(@StringRes val title: Int, val enabled: State<Boolean>, val click: () -> Unit)
private class SaveSlot {
    val actions = mutableStateListOf<SaveAction>()
}
private val LocalSettingsSave = staticCompositionLocalOf<SaveSlot?> { null }

@Composable
internal fun SettingsSaveAction(visible: Boolean, @StringRes title: Int, enabled: Boolean, onClick: () -> Unit) {
    if (!visible) return
    val slot = LocalSettingsSave.current
    if (slot == null) {
        SettingsSaveButton(title, enabled, Modifier.fillMaxWidth(), onClick)
        return
    }
    val click by rememberUpdatedState(onClick)
    val available = rememberUpdatedState(enabled)
    DisposableEffect(slot, title) {
        val action = SaveAction(title, available) { click() }
        slot.actions.add(action)
        onDispose { slot.actions.remove(action) }
    }
}

@Composable
internal fun SettingsScroll(withImePadding: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val slot = remember { SaveSlot() }
    val density = LocalDensity.current
    val noticeClearance = LocalNavigationClearance.current
    val buttons = slot.actions.toList()
    var contentHeight by remember { mutableIntStateOf(0) }
    var buttonHeight by remember { mutableStateOf(52.dp) }
    val edge = 16.dp
    val bottom = 10.dp
    BoxWithConstraints(Modifier.fillMaxSize().editorialSideBottomInsets()
        .then(if (withImePadding) Modifier.imePadding() else Modifier).clipToBounds()) {
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).onSizeChanged { contentHeight = it.height }
            .padding(start = edge, end = edge, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val column = this
            CompositionLocalProvider(LocalSettingsSave provides slot) { column.content() }
            if (buttons.isNotEmpty()) Spacer(Modifier.height(buttonHeight))
        }
        if (buttons.isNotEmpty()) {
            val maxScroll = scroll.maxValue
            val distance = if (maxScroll == Int.MAX_VALUE) Int.MAX_VALUE else maxScroll - scroll.value
            val blend = (1f - distance / with(density) { 112.dp.toPx() }).coerceIn(0f, 1f)
            val lastY = (maxHeight - buttonHeight - bottom).coerceAtLeast(0.dp)
            val floatY = (maxHeight - buttonHeight - noticeClearance).coerceAtLeast(0.dp)
            val inlineY = if (maxScroll == 0 && contentHeight > 0)
                (with(density) { contentHeight.toDp() } - bottom - buttonHeight).coerceIn(0.dp, lastY) else lastY
            val width = (maxWidth - edge * 2).coerceAtLeast(0.dp)
            val floatWidth = navigationBarWidth(width.coerceAtMost(navigationMaxWidth)) * .85f
            Column(
                Modifier.align(Alignment.TopCenter).zIndex(1f)
                    .offset { IntOffset(0, lerp(floatY, inlineY, blend).roundToPx()) }
                    .width(lerp(floatWidth, width, blend)).onSizeChanged {
                        with(density) { buttonHeight = it.height.toDp() }
                    }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                buttons.forEach { button -> key(button) {
                    SettingsSaveButton(button.title, button.enabled.value, Modifier.fillMaxWidth(), button.click)
                } }
            }
        }
    }
}

@Composable
private fun SettingsSaveButton(@StringRes title: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.readingNavigationGlass(noticeGlassShape)
        .entryGrayWash()
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(ui(title), color = Color.Black.copy(alpha = if (enabled) 1f else .56f),
            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}
