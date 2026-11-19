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
    val surface: Color,
    val card: Color,
    val elevated: Color,
) {
    Graphite("graphite", R.string.theme_graphite, Color(0xFF0A0C10), Color(0xFFF6A15A), Color(0xFFE8B884),
        Color(0xFFF5F5F3), Color(0xFFA5A8B0), Color(0xFF14171D), Color(0xFF20242B), Color(0xFF30353D)),
    Amber("amber", R.string.theme_amber, Color(0xFF181310), Color(0xFFDAA45D), Color(0xFFF0BF81),
        Color(0xFFF7EFE7), Color(0xFFCDC0B4), Color(0xFF211A16), Color(0xFF2A211B), Color(0xFF382D25)),
    Ocean("ocean", R.string.theme_ocean, Color(0xFF111820), Color(0xFF75ADE1), Color(0xFF9ED1FC),
        Color(0xFFEFF4FC), Color(0xFFAFBCCA), Color(0xFF19232D), Color(0xFF202D3A), Color(0xFF293C4D)),
    Jade("jade", R.string.theme_jade, Color(0xFF101B1A), Color(0xFF63C0A2), Color(0xFF99EBD0),
        Color(0xFFEFF6F3), Color(0xFFABBCB4), Color(0xFF172420), Color(0xFF1E2E28), Color(0xFF273D33)),
    Amethyst("amethyst", R.string.theme_amethyst, Color(0xFF18141F), Color(0xFFB08ADD), Color(0xFFDABBFA),
        Color(0xFFF5EFFC), Color(0xFFC0B4CC), Color(0xFF201A29), Color(0xFF2B2236), Color(0xFF382B47));

    val tertiaryText get() = lerp(muted, background, 0.24f)
    val success get() = Color(0xFF9BC9A7)
    val warning get() = Color(0xFFDBBA83)
    val danger get() = Color(0xFFF2A6AA)

    fun panel(amount: Float) = lerp(background, tint, amount)

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: Graphite
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
