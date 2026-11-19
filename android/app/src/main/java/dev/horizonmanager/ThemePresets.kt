package dev.horizonmanager

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal enum class ThemePreset(
    val id: String,
    @param:StringRes val title: Int,
    val background: Color,
    val tint: Color,
    val link: Color,
    val text: Color,
    val muted: Color,
) {
    Ocean("ocean", R.string.theme_ocean, Color(0xFF080F1D), Color(0xFF398ED5), Color(0xFFA0DAFF), Color(0xFFFAFAFA), Color(0xFFD2D2D2)),
    Graphite("graphite", R.string.theme_graphite, Color(0xFF121314), Color(0xFF92969B), Color(0xFFE1E1E1), Color.White, Color(0xFFCCCCCC)),
    Jade("jade", R.string.theme_jade, Color(0xFF081715), Color(0xFF2EAA87), Color(0xFF99EBD0), Color(0xFFF8F8F8), Color(0xFFC9C9C9)),
    Amethyst("amethyst", R.string.theme_amethyst, Color(0xFF160D20), Color(0xFF975BDA), Color(0xFFE0BAFF), Color(0xFFFCFCFC), Color(0xFFD7D7D7)),
    Amber("amber", R.string.theme_amber, Color(0xFF1B120C), Color(0xFFC47E34), Color(0xFFFFD4A2), Color(0xFFF5F5F5), Color(0xFFDADADA));

    fun panel(amount: Float) = lerp(background, tint, amount)

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: Ocean
    }
}

@Composable
internal fun ThemeSettings(current: ThemePreset, select: (ThemePreset) -> Unit) {
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (theme in ThemePreset.entries) {
            Tile(Modifier.selectable(current == theme, role = Role.RadioButton, onClick = { select(theme) })) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Canvas(Modifier.size(43.dp)) {
                        drawCircle(theme.tint.copy(alpha = 0.65f))
                        drawCircle(theme.background, radius = size.minDimension * 0.46f)
                        drawCircle(theme.tint, radius = size.minDimension * 0.27f)
                        drawCircle(theme.link.copy(alpha = 0.9f), radius = size.minDimension * 0.09f,
                            center = Offset(size.width * 0.65f, size.height * 0.35f))
                    }
                    Text(ui(theme.title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (current == theme) Icon(Icons.Default.Check, null, tint = theme.link)
                }
            }
        }
    }
}
