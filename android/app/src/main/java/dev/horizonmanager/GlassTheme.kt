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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip as MaterialFilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.Switch as MaterialSwitch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LocalHorizonPalette = staticCompositionLocalOf { HorizonPalette }
internal val LocalSettingsGlass = staticCompositionLocalOf { false }
internal val LocalLightGlass = staticCompositionLocalOf { false }

@Composable
internal fun readingBarTitleStyle() =
    if (LocalConfiguration.current.screenWidthDp < 350) MaterialTheme.typography.headlineMedium
    else MaterialTheme.typography.displaySmall

private fun HorizonPalette.colors() = darkColorScheme(
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
internal fun HorizonTheme(content: @Composable () -> Unit) {
    val colors = remember { HorizonPalette.colors() }
    MaterialTheme(colorScheme = colors, typography = textStyles, shapes = Shapes(
        extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(15.dp),
        medium = RoundedCornerShape(21.dp), large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(34.dp),
    )) {
        CompositionLocalProvider(LocalContentColor provides HorizonPalette.text, content = content)
    }
}

@Composable
internal fun Button(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (LocalSettingsGlass.current) SettingsAction(onClick, enabled, content = content)
    else MaterialButton(onClick, enabled = enabled, content = content)
}

internal fun Modifier.settingsControlGlass(enabled: Boolean = true): Modifier =
    if (enabled) glassSurface(feedGlassShape, lightNavigation = true, outlined = false)
    else clip(feedGlassShape).background(Color(0xFFA0A0A0))

@Composable
internal fun CompactSettingsButton(title: Int, enabled: Boolean, selected: Boolean, wash: State<Float>,
                                   onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val surface = Modifier.settingsControlGlass(enabled)
        .then(if (selected || pressed) Modifier.savedNoticeWash(NoticeWash.Connected, phase = wash) else Modifier)
    Box(Modifier.heightIn(min = 48.dp).clickable(interactionSource = interaction,
        indication = androidx.compose.foundation.LocalIndication.current,
        enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(ui(title), surface.padding(horizontal = 12.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelLarge, color = if (enabled) ReadingGlass.text else ReadingGlass.muted)
    }
}

@Composable
private fun SettingsAction(onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier,
                           content: @Composable RowScope.() -> Unit) {
    val face = if (LocalLightGlass.current) Modifier.heightIn(min = 48.dp)
        .glassSurface(feedGlassShape, lightNavigation = true, outlined = enabled)
    else Modifier.settingsControlGlass(enabled)
    MaterialTextButton(
        onClick, modifier.then(face), enabled = enabled, shape = feedGlassShape,
        colors = ButtonDefaults.textButtonColors(contentColor = ReadingGlass.text, disabledContentColor = ReadingGlass.muted),
        content = content,
    )
}

@Composable
internal fun SettingsActionPair(first: Int, second: Int, firstEnabled: Boolean, secondEnabled: Boolean,
                                onFirst: () -> Unit, onSecond: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SettingsAction(onFirst, firstEnabled, Modifier.weight(1f)) { Text(ui(first), textAlign = TextAlign.Center) }
        SettingsAction(onSecond, secondEnabled, Modifier.weight(1f)) { Text(ui(second), textAlign = TextAlign.Center) }
    }
}

@Composable
internal fun FilterChip(
    selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    enabled: Boolean = true, colors: SelectableChipColors = FilterChipDefaults.filterChipColors(),
) {
    val settings = LocalSettingsGlass.current
    if (settings) MaterialFilterChip(
        selected, onClick, label, enabled = enabled, shape = feedGlassShape, border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = ReadingGlass.base, labelColor = ReadingGlass.text,
            selectedContainerColor = ReadingGlass.muted, selectedLabelColor = Color.White,
            disabledContainerColor = Color(0xFFA0A0A0), disabledLabelColor = ReadingGlass.muted,
        ),
    ) else MaterialFilterChip(selected, onClick, label, enabled = enabled, colors = colors)
}

@Composable
internal fun TextButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (LocalSettingsGlass.current) {
        SettingsAction(onClick, enabled, content = content)
        return
    }
    val palette = LocalHorizonPalette.current
    MaterialTextButton(onClick, enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = palette.text, disabledContentColor = palette.muted.copy(alpha = 0.70f)),
        content = content)
}

@Composable
internal fun OutlinedButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (LocalSettingsGlass.current) {
        SettingsAction(onClick, enabled, content = content)
        return
    }
    val palette = LocalHorizonPalette.current
    MaterialOutlinedButton(onClick, enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.text, disabledContentColor = palette.muted.copy(alpha = 0.70f)),
        content = content)
}

@Composable
internal fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true,
                    inverted: Boolean = false) {
    val colors = if (!LocalSettingsGlass.current) SwitchDefaults.colors()
    else if (inverted) SwitchDefaults.colors(
        checkedThumbColor = ReadingGlass.muted, checkedTrackColor = ReadingGlass.base,
        checkedBorderColor = ReadingGlass.muted,
        uncheckedThumbColor = Color.White, uncheckedTrackColor = ReadingGlass.muted,
        uncheckedBorderColor = Color.Transparent,
    ) else SwitchDefaults.colors(
        checkedThumbColor = Color.White, checkedTrackColor = ReadingGlass.muted,
        uncheckedThumbColor = ReadingGlass.muted, uncheckedTrackColor = ReadingGlass.base,
        uncheckedBorderColor = ReadingGlass.muted,
    )
    MaterialSwitch(checked, onCheckedChange, enabled = enabled, colors = colors)
}

@Composable
internal fun horizonFieldShape() = if (LocalSettingsGlass.current) feedGlassShape else OutlinedTextFieldDefaults.shape

@Composable
internal fun horizonFieldColors() = if (LocalLightGlass.current) OutlinedTextFieldDefaults.colors(
    focusedTextColor = ReadingGlass.text, unfocusedTextColor = ReadingGlass.text, cursorColor = ReadingGlass.text,
    focusedLabelColor = ReadingGlass.text, unfocusedLabelColor = ReadingGlass.muted,
    focusedBorderColor = ReadingGlass.muted, unfocusedBorderColor = ReadingGlass.muted.copy(alpha = .55f),
    focusedContainerColor = Color.White.copy(alpha = .35f), unfocusedContainerColor = Color.White.copy(alpha = .23f),
    focusedTrailingIconColor = ReadingGlass.text, unfocusedTrailingIconColor = ReadingGlass.muted,
) else LocalHorizonPalette.current.let { palette ->
    val accent = if (LocalSettingsGlass.current) ReadingGlass.raised else palette.link
    OutlinedTextFieldDefaults.colors(focusedLabelColor = palette.text, focusedBorderColor = accent, cursorColor = accent)
}

@Composable
internal fun GlassLayout(tabs: List<HorizonTab>, selection: Int, showTabs: Boolean, select: (Int) -> Unit,
                         unread: Int, overlay: ReadingOverlay = ReadingOverlay.None,
                         statusGlassInContent: Boolean = false,
                         modal: @Composable () -> Unit = {},
                         content: @Composable BoxScope.() -> Unit) {
    val palette = LocalHorizonPalette.current
    val sceneLayer = rememberGraphicsLayer()
    val scene = remember(sceneLayer) { GlassScene(sceneLayer) }
    var clearance by remember { mutableStateOf(defaultNavigationClearance) }
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
                if (!statusGlassInContent) StatusGlass()
                AnimatedVisibility(showTabs, modifier = Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(start = 16.dp, end = 16.dp, bottom = navigationBottomGap),
                    enter = fadeIn(tween(160)) + slideInVertically(tween(navigationSlideMillis)) { it * 2 },
                    exit = fadeOut(tween(140)) + slideOutVertically(tween(navigationSlideMillis)) { it * 2 }) {
                    BoxWithConstraints(Modifier.widthIn(max = navigationMaxWidth).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        GlassNavigation(tabs, selection, unread, navigationBarWidth(maxWidth), overlay, measured = {
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
    val barHeight = readingBarHeight()
    var toolbarHeight by remember { mutableStateOf(barHeight) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().recordScene(scene).background(settingsBackdrop)) {
            MaterialTheme(colorScheme = settingsColors) {
                CompositionLocalProvider(LocalSettingsGlass provides true, LocalLightGlass provides true,
                    LocalContentColor provides ReadingGlass.text, LocalMenuScene provides scene) {
                    content(statusInset + toolbarHeight + 16.dp)
                }
            }
        }
        CompositionLocalProvider(LocalGlassScene provides scene) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 12.dp, end = 12.dp, top = 5.dp, bottom = 4.dp)
                .onSizeChanged { toolbarHeight = with(density) { it.height.toDp() } }
                .readingNavigationGlass(feedGlassShape)
                .pointerInput(Unit) { detectTapGestures {} }
                .heightIn(min = barHeight).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalLightGlass provides true) {
                    Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) {
                        if (back != null) {
                            GlassIconAction(Icons.AutoMirrored.Filled.ArrowBack, ui(R.string.ui_back), onClick = back)
                        }
                    }
                    Text(heading, Modifier.weight(1f).padding(horizontal = 2.dp, vertical = 8.dp).semantics { heading() },
                        style = MaterialTheme.typography.headlineSmall, color = ReadingGlass.text,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) { action?.invoke() }
                }
            }
        }
    }
}

private val settingsBackdrop = Brush.verticalGradient(listOf(Color(0xFFB0B0B0), Color(0xFF909090)))
private val settingsColors = lightColorScheme(
    primary = ReadingGlass.muted, onPrimary = Color.White,
    primaryContainer = ReadingGlass.raised, onPrimaryContainer = ReadingGlass.text,
    secondary = ReadingGlass.muted, onSecondary = Color.White,
    secondaryContainer = ReadingGlass.base, onSecondaryContainer = ReadingGlass.text,
    tertiary = ReadingGlass.muted, onTertiary = Color.White,
    tertiaryContainer = ReadingGlass.base, onTertiaryContainer = ReadingGlass.text,
    background = Color(0xFFA0A0A0), onBackground = ReadingGlass.text,
    surface = ReadingGlass.base, onSurface = ReadingGlass.text, surfaceTint = Color.Transparent,
    surfaceVariant = ReadingGlass.base, onSurfaceVariant = ReadingGlass.muted,
    surfaceContainerLow = ReadingGlass.raised, surfaceContainer = ReadingGlass.base,
    surfaceContainerHigh = Color(0xFFD4D4D4), surfaceContainerHighest = Color(0xFFCBCBCB),
    outline = Color(0xFF666666), outlineVariant = Color(0xFF909090), error = Color(0xFF791B26),
)

@Composable
internal fun Tile(modifier: Modifier = Modifier, accented: Boolean = false,
                  content: @Composable ColumnScope.() -> Unit) {
    val light = LocalLightGlass.current
    val shape = if (light) feedGlassShape else MaterialTheme.shapes.large
    val palette = LocalHorizonPalette.current
    val face = if (accented) palette.elevated else palette.card
    val surface = when {
        light && LocalSettingsGlass.current -> Modifier.readingNavigationGlass(shape)
        light -> Modifier.glassSurface(shape, lightNavigation = true)
        LocalSettingsGlass.current -> Modifier.glassSurface(shape, raised = accented)
        else -> Modifier.clip(shape).background(face)
    }
    Column(
        Modifier.fillMaxWidth().then(surface)
            .then(modifier).padding(horizontal = 15.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        content = content,
    )
}

@Composable
internal fun Modifier.settingsItemSurface(highlighted: Boolean = false): Modifier {
    if (LocalLightGlass.current && LocalSettingsGlass.current) {
        return clip(feedGlassShape).background(ReadingGlass.raised.copy(alpha = if (highlighted) .95f else .48f))
    }
    val palette = LocalHorizonPalette.current
    return clip(MaterialTheme.shapes.medium)
        .background(if (highlighted) palette.glassSelection else palette.elevated.copy(alpha = .58f))
}

@Composable
internal fun SettingNote(text: String, color: Color = ReadingGlass.muted) {
    Text(text, Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
internal fun GlassSettingRow(title: String, subtitle: String, enabled: Boolean = true,
                             @StringRes help: Int? = null, open: () -> Unit) {
    val palette = LocalHorizonPalette.current
    val settings = LocalSettingsGlass.current
    val light = settings || LocalLightGlass.current
    val text = if (light) ReadingGlass.text else palette.text
    val muted = if (light) ReadingGlass.muted else palette.muted
    val rowSurface = when {
        settings -> Modifier.settingsControlGlass(enabled)
        light -> Modifier.clip(feedGlassShape).background(Color(0xFFD3D3D3))
        else -> Modifier
    }
    Row(rowSurface.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(enabled = enabled, role = Role.Button, onClick = open)
            .heightIn(min = 57.dp).padding(horizontal = if (light) 14.dp else 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = if (enabled) text else muted)
                if (subtitle.isNotBlank()) Text(subtitle, color = muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("›", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleLarge,
                color = if (light) muted else palette.link)
        }
        if (help != null) SettingHelp(title, help)
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
        light = LocalLightGlass.current || LocalSettingsGlass.current,
    )
}

@Composable
internal fun SettingHeading(title: String, @StringRes help: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        SettingHelp(title, help)
    }
}
