package dev.horizonmanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun GlassDropdownMenu(
    expanded: Boolean, onDismissRequest: () -> Unit, title: String, content: @Composable ColumnScope.() -> Unit,
) {
    if (expanded) GlassPickerDialog(title, onDismissRequest) {
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
internal fun GlassPickerDialog(title: String, close: () -> Unit, secondary: (@Composable () -> Unit)? = null,
                               content: @Composable () -> Unit) {
    HorizonAlertDialog(close, { Text(title) }, content,
        confirmButton = { TextButton(onClick = close) { Text(ui(R.string.ui_close)) } },
        dismissButton = secondary, light = true)
}

@Composable
internal fun GlassPickerItem(text: @Composable () -> Unit, onClick: () -> Unit, enabled: Boolean = true,
                             selected: Boolean? = null, supportingContent: (@Composable () -> Unit)? = null) {
    val surface = Modifier.fillMaxWidth().glassSurface(feedGlassShape, lightNavigation = true, outlined = selected == true)
        .then(if (selected == true) Modifier.savedNoticeWash(NoticeWash.Connected) else Modifier)
    val interaction = if (selected == null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    val alpha = if (enabled) 1f else .55f
    ListItem(
        headlineContent = text,
        supportingContent = supportingContent,
        trailingContent = if (selected == true) ({
            Text(ui(R.string.ui_selected), style = MaterialTheme.typography.labelSmall)
        }) else null,
        modifier = surface.then(interaction),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent,
            headlineColor = ReadingGlass.text.copy(alpha = alpha), supportingColor = ReadingGlass.muted.copy(alpha = alpha),
            trailingIconColor = ReadingGlass.text.copy(alpha = alpha)),
    )
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
