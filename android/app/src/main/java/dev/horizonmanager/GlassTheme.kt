package dev.horizonmanager

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LocalHorizonPalette = staticCompositionLocalOf { ThemePreset.Graphite }
private val LocalSettingsGlass = staticCompositionLocalOf { false }
private val LocalSettingsTile = staticCompositionLocalOf { false }

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
                         unread: Int, overlay: ReadingOverlay = ReadingOverlay.None,
                         modal: @Composable () -> Unit = {},
                         content: @Composable BoxScope.() -> Unit) {
    val palette = LocalHorizonPalette.current
    val sceneLayer = rememberGraphicsLayer()
    val scene = remember(sceneLayer) { GlassScene(sceneLayer) }
    var clearance by remember { mutableStateOf(defaultNavigationClearance) }
    val statusHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(Modifier.fillMaxSize().background(palette.background).imePadding()) {
        Box(Modifier.fillMaxSize().then(
            if (overlay == ReadingOverlay.Article) Modifier.blur(5.dp) else Modifier
        )) {
            CompositionLocalProvider(LocalNavigationClearance provides clearance, LocalMenuScene provides scene) {
                Box(Modifier.fillMaxSize().recordScene(scene).background(palette.background).drawBehind {
                    val center = androidx.compose.ui.geometry.Offset(size.width * .15f, size.height * 1.02f)
                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(palette.tint.copy(alpha = .095f), Color.Transparent), center, size.width * .64f),
                        radius = size.width * .64f, center = center)
                }, content = content)
            }
            CompositionLocalProvider(LocalGlassScene provides scene) {
                if (statusHeight > 0.dp) {
                    Spacer(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(statusHeight * (1f + statusGlassFadeFraction))
                        .statusBarGlass())
                }
                AnimatedVisibility(showTabs, modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(start = 16.dp, end = 16.dp, bottom = navigationBottomGap),
                    enter = fadeIn(tween(160)) + slideInVertically(tween(navigationSlideMillis)) { it * 2 },
                    exit = fadeOut(tween(140)) + slideOutVertically(tween(navigationSlideMillis)) { it * 2 }) {
                    BoxWithConstraints(Modifier.widthIn(max = 520.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        val width = ((maxWidth / 2).coerceAtLeast(156.dp) * 1.5f).coerceAtMost(maxWidth)
                        GlassNavigation(tabs, selection, unread, width, overlay, measured = {
                            clearance = it + navigationBottomGap + 8.dp
                        }, select = select)
                    }
                }
            }
        }
        CompositionLocalProvider(LocalGlassScene provides scene, LocalMenuScene provides scene) {
            GlassNoticeHost(bottomClearance = clearance)
            modal()
        }
    }
}

@Composable
internal fun SettingsPage(
    heading: String, back: (() -> Unit)?, action: (@Composable () -> Unit)? = null,
    content: @Composable (Dp) -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val scene = remember(layer) { GlassScene(layer) }
    val density = LocalDensity.current
    val statusInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    var toolbarHeight by remember { mutableStateOf(54.dp) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().recordScene(scene)) {
            CompositionLocalProvider(LocalSettingsGlass provides true) {
                content(statusInset + toolbarHeight + 16.dp)
            }
        }
        CompositionLocalProvider(LocalGlassScene provides scene) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .onSizeChanged { toolbarHeight = with(density) { it.height.toDp() } }
                .navigationGlass(feedGlassShape)
                .pointerInput(Unit) { detectTapGestures {} }
                .heightIn(min = 54.dp).padding(start = if (back == null) 18.dp else 4.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (back != null) {
                    GlassIconAction(Icons.AutoMirrored.Filled.ArrowBack,
                        ui(R.string.ui_back), onClick = back)
                }
                Text(heading, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.titleLarge)
                action?.invoke()
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
    val surface = if (LocalSettingsGlass.current) Modifier.glassSurface(shape, raised = accented)
        else Modifier.clip(shape).background(face)
    Column(
        Modifier.fillMaxWidth().then(surface)
            .then(modifier).padding(horizontal = 15.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        CompositionLocalProvider(LocalSettingsTile provides true) { content() }
    }
}

@Composable
internal fun GlassSettingRow(title: String, subtitle: String, enabled: Boolean = true,
                             @StringRes help: Int? = null, open: () -> Unit) {
    val palette = LocalHorizonPalette.current
    val standalone = LocalSettingsGlass.current && !LocalSettingsTile.current
    Column(if (standalone) Modifier.glassSurface(MaterialTheme.shapes.large).padding(horizontal = 10.dp) else Modifier) {
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
        if (!standalone) androidx.compose.material3.HorizontalDivider(
            color = palette.text.copy(alpha = .10f), thickness = .5.dp,
        )
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
