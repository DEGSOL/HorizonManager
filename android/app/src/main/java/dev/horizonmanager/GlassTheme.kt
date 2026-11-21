package dev.horizonmanager

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LocalHorizonPalette = staticCompositionLocalOf { ThemePreset.Graphite }

private fun ThemePreset.colors() = darkColorScheme(
    primary = tint, onPrimary = Color(0xFF19130E),
    primaryContainer = elevated, onPrimaryContainer = text,
    secondary = tint, onSecondary = Color(0xFF19130E), secondaryContainer = elevated, onSecondaryContainer = text,
    tertiary = link, onTertiary = Color(0xFF19130E), tertiaryContainer = elevated, onTertiaryContainer = text,
    inversePrimary = tint, inverseSurface = elevated, inverseOnSurface = text,
    background = background, onBackground = text, surface = surface, onSurface = text,
    surfaceDim = background, surfaceBright = elevated, surfaceTint = tint, onSurfaceVariant = muted,
    surfaceVariant = card, surfaceContainerLowest = background,
    surfaceContainerLow = surface, surfaceContainer = card,
    surfaceContainerHigh = elevated, surfaceContainerHighest = elevated,
    outline = tertiaryText, outlineVariant = elevated,
    error = danger, onError = Color(0xFF260F12), errorContainer = Color(0xFF512C35), onErrorContainer = text,
)

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0): TextStyle =
    TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp,
        lineHeight = line.sp, letterSpacing = tracking.sp)

private val textStyles = Typography(
    displayLarge = type(52, 56, FontWeight.Bold, -1.5),
    displayMedium = type(44, 48, FontWeight.Bold, -1.3),
    displaySmall = type(39, 43, FontWeight.Bold, -1.0),
    headlineLarge = type(34, 39, FontWeight.Bold, -0.8),
    headlineMedium = type(30, 35, FontWeight.Bold, -0.6),
    headlineSmall = type(25, 30, FontWeight.SemiBold, -0.5),
    titleLarge = type(20, 26, FontWeight.SemiBold, -0.35),
    titleMedium = type(18, 24, FontWeight.SemiBold, -0.2),
    titleSmall = type(15, 21, FontWeight.SemiBold, -0.1),
    bodyLarge = type(16, 24, tracking = -0.15),
    bodyMedium = type(15, 22, tracking = -0.1),
    bodySmall = type(13, 18),
    labelLarge = type(14, 18, FontWeight.SemiBold),
    labelMedium = type(12, 16, FontWeight.Medium, 0.1),
    labelSmall = type(11, 14, FontWeight.Medium, 0.1),
)

@Composable
internal fun HorizonTheme(preset: ThemePreset, content: @Composable () -> Unit) {
    val colors = remember(preset) { preset.colors() }
    MaterialTheme(colorScheme = colors, typography = textStyles, shapes = Shapes(
        extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(15.dp),
        medium = RoundedCornerShape(21.dp), large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(34.dp),
    )) {
        CompositionLocalProvider(LocalHorizonPalette provides preset, LocalContentColor provides preset.text, content = content)
    }
}

@Composable
internal fun TextButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val palette = LocalHorizonPalette.current
    MaterialTextButton(onClick, enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = palette.text, disabledContentColor = palette.muted.copy(alpha = 0.70f)),
        content = content)
}

@Composable
internal fun OutlinedButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    val palette = LocalHorizonPalette.current
    MaterialOutlinedButton(onClick, enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.text, disabledContentColor = palette.muted.copy(alpha = 0.70f)),
        content = content)
}

@Composable
internal fun horizonFieldColors() = LocalHorizonPalette.current.let { palette ->
    OutlinedTextFieldDefaults.colors(focusedLabelColor = palette.text, focusedBorderColor = palette.link, cursorColor = palette.link)
}

@Composable
internal fun GlassLayout(tabs: List<HorizonTab>, selection: Int, showTabs: Boolean, select: (Int) -> Unit,
                         unread: Int, dimmed: Boolean = false, savedNotice: Boolean = false,
                         content: @Composable BoxScope.() -> Unit) {
    val palette = LocalHorizonPalette.current
    val sceneLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val scene = remember(sceneLayer) { GlassScene(sceneLayer) }
    var clearance by remember { mutableStateOf(defaultNavigationClearance) }
    Box(Modifier.fillMaxSize().background(palette.background).drawBehind {
        val center = androidx.compose.ui.geometry.Offset(size.width * .15f, size.height * 1.02f)
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
            listOf(palette.tint.copy(alpha=.095f), Color.Transparent), center, size.width*.64f),
            radius=size.width*.64f, center=center)
    }.imePadding()) {
        CompositionLocalProvider(LocalNavigationClearance provides clearance) {
            Box(Modifier.fillMaxSize().recordScene(scene), content = content)
        }
        CompositionLocalProvider(LocalGlassScene provides scene) {
            AnimatedVisibility(savedNotice, modifier = Modifier.align(Alignment.BottomCenter)
                .navigationBarsPadding().padding(bottom = if (showTabs) clearance else 14.dp, start = 18.dp, end = 18.dp),
                enter = fadeIn(tween(170)) + slideInVertically(tween(190)) { it / 3 },
                exit = fadeOut(tween(220)) + slideOutVertically(tween(220)) { it / 3 }) {
                Box(Modifier.frost(MaterialTheme.shapes.large)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .padding(horizontal = 22.dp, vertical = 12.dp)) {
                    Text(ui(R.string.hovq_settings_saved), style = MaterialTheme.typography.labelLarge)
                }
            }
            AnimatedVisibility(showTabs, modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, end = 16.dp, bottom = navigationBottomGap),
                enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 2 },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(190)) { it / 2 }) {
                GlassNavigation(tabs, selection, unread, dimmed, measured = {
                    clearance = it + navigationBottomGap + 8.dp
                }, select = select)
            }
        }
    }
}

@Composable
internal fun Tile(modifier: Modifier = Modifier, accented: Boolean = false,
                  content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    val palette = LocalHorizonPalette.current
    val face = if (accented) palette.elevated else palette.card
    Column(
        Modifier.fillMaxWidth().clip(shape).background(face)
            .then(modifier).padding(horizontal = 15.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        content = content,
    )
}

@Composable
internal fun GlassSettingRow(title: String, subtitle: String, enabled: Boolean = true,
                             @StringRes help: Int? = null, open: () -> Unit) {
    val palette = LocalHorizonPalette.current
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(enabled = enabled, role = Role.Button, onClick = open)
                .heightIn(min = 57.dp).padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) palette.text else palette.muted)
                    if (subtitle.isNotBlank()) Text(subtitle, color = palette.muted, style = MaterialTheme.typography.bodySmall)
                }
                Text("›", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleLarge, color = palette.link)
            }
            if (help != null) SettingHelp(title, help)
        }
        androidx.compose.material3.HorizontalDivider(color = palette.elevated.copy(alpha = .65f), thickness = .5.dp)
    }
}

@Composable
internal fun SettingHelp(title: String, @StringRes description: Int) {
    var explaining by remember { mutableStateOf(false) }
    GlassIconAction(ImageVector.vectorResource(R.drawable.ic_help), ui(R.string.setting_help_for, title)) { explaining = true }
    if (explaining) HorizonAlertDialog(
        onDismissRequest = { explaining = false },
        title = { Text(title) },
        text = { Text(ui(description)) },
        confirmButton = { TextButton(onClick = { explaining = false }) { Text(ui(R.string.ui_close)) } },
    )
}

@Composable
internal fun SettingHeading(title: String, @StringRes help: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        SettingHelp(title, help)
    }
}

@Composable
internal fun Modifier.glassPanel(shape: Shape, raised: Boolean = false): Modifier {
    val palette = LocalHorizonPalette.current
    return clip(shape)
        .background((if (raised) palette.card else palette.elevated).copy(alpha = 0.96f))
        .border(1.dp, palette.muted.copy(alpha = 0.15f), shape)
}

@Composable
internal fun GlassToolbarButton(title: String, onClick: () -> Unit) {
    Box(Modifier.glassPanel(CircleShape).clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 15.dp), contentAlignment = Alignment.Center) {
        Text(title, color = LocalHorizonPalette.current.text, style = MaterialTheme.typography.labelLarge)
    }
}
