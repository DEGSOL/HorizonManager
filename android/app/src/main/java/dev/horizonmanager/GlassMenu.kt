package dev.horizonmanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
internal fun GlassDropdownMenu(
    expanded: Boolean, onDismissRequest: () -> Unit, title: String, content: @Composable ColumnScope.() -> Unit,
) {
    if (expanded) GlassPickerDialog(title, onDismissRequest) {
        Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassPickerDialog(title: String, close: () -> Unit, secondary: (@Composable () -> Unit)? = null,
                               content: @Composable () -> Unit) {
    Dialog(onDismissRequest = close) {
        DialogMotion()
        MaterialTheme(colorScheme = settingsColors) {
            CompositionLocalProvider(LocalGlassScene provides LocalMenuScene.current,
                LocalSettingsGlass provides true, LocalLightGlass provides true, LocalContentColor provides ReadingGlass.text) {
                val dividerColor = ReadingGlass.muted.copy(alpha = .23f)
                Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().readingNavigationGlass(feedGlassShape, menu = true)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(title, Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    HorizontalDivider(color = dividerColor)
                    Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium, content)
                    }
                    HorizontalDivider(color = dividerColor)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        secondary?.invoke()
                        SettingsAction(close, enabled = true, modifier = Modifier.widthIn(min = 112.dp)) {
                            Text(ui(R.string.ui_close), textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GlassPickerItem(text: @Composable () -> Unit, onClick: () -> Unit, enabled: Boolean = true,
                             selected: Boolean? = null, supportingContent: (@Composable () -> Unit)? = null) {
    val rowShape = RoundedCornerShape(15.dp)
    val color = if (enabled) ReadingGlass.text else Color(0xFF565656)
    val interaction = if (selected == null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    Row(Modifier.fillMaxWidth().glassSurface(rowShape, lightNavigation = true)
        .then(if (selected == true) Modifier.savedNoticeWash(NoticeWash.Connected) else Modifier)
        .then(interaction).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (selected != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = color)
                else RadioButton(selected = false, onClick = null, enabled = enabled,
                    colors = RadioButtonDefaults.colors(unselectedColor = color, disabledUnselectedColor = color))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            CompositionLocalProvider(LocalContentColor provides color) {
                ProvideTextStyle(MaterialTheme.typography.titleSmall, text)
            }
            if (supportingContent != null) {
                CompositionLocalProvider(LocalContentColor provides if (enabled) ReadingGlass.muted else color) {
                    ProvideTextStyle(MaterialTheme.typography.bodySmall, supportingContent)
                }
            }
        }
    }
}

@Composable
internal fun JournalDropdownMenu(
    expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    val light = LocalLightGlass.current || LocalSettingsGlass.current
    val shape = if (light) feedGlassShape else RoundedCornerShape(22.dp)
    val scheme = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalGlassScene provides LocalMenuScene.current) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = Modifier.glassSurface(shape, backdrop = true, menu = true, lightNavigation = light),
            shape = shape,
            containerColor = Color.Transparent,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            content = {
                if (light) {
                    val column = this
                    MaterialTheme(colorScheme = scheme.copy(onSurface = ReadingGlass.text, onSurfaceVariant = ReadingGlass.muted)) {
                        CompositionLocalProvider(LocalLightGlass provides true) { column.content() }
                    }
                } else content()
            },
        )
    }
}
