package dev.horizonmanager

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun GlassDropdownMenu(
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
