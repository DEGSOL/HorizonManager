package dev.horizonmanager

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LocalHorizonPalette = staticCompositionLocalOf { ThemePreset.Ocean }

private fun ThemePreset.glassEdge() = Brush.linearGradient(
    listOf(text.copy(alpha = 0.55f), link.copy(alpha = 0.27f), tint.copy(alpha = 0.20f), link.copy(alpha = 0.39f)),
)

private fun ThemePreset.colors() = darkColorScheme(
    primary = panel(0.50f), onPrimary = text,
    primaryContainer = panel(0.30f), onPrimaryContainer = text,
    secondary = tint, onSecondary = text, secondaryContainer = panel(0.33f), onSecondaryContainer = text,
    tertiary = link, onTertiary = text, tertiaryContainer = panel(0.34f), onTertiaryContainer = text,
    inversePrimary = panel(0.39f), inverseSurface = panel(0.29f), inverseOnSurface = text,
    background = background, onBackground = text, surface = panel(0.06f), onSurface = text,
    surfaceDim = background, surfaceBright = panel(0.22f), surfaceTint = tint, onSurfaceVariant = muted,
    surfaceVariant = panel(0.20f), surfaceContainerLowest = background,
    surfaceContainerLow = panel(0.10f), surfaceContainer = panel(0.14f),
    surfaceContainerHigh = panel(0.21f), surfaceContainerHighest = panel(0.25f),
    outline = muted.copy(alpha = 0.57f), outlineVariant = tint.copy(alpha = 0.42f),
    error = text, onError = text, errorContainer = Color(0xFF512C35), onErrorContainer = text,
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
    titleLarge = type(21, 27, FontWeight.SemiBold, -0.35),
    titleMedium = type(17, 23, FontWeight.SemiBold, -0.2),
    titleSmall = type(15, 21, FontWeight.SemiBold, -0.1),
    bodyLarge = type(17, 25, tracking = -0.15),
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
        medium = RoundedCornerShape(21.dp), large = RoundedCornerShape(27.dp),
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

internal data class HorizonTab(@param:StringRes val label: Int, val icon: ImageVector)

@Composable
internal fun GlassLayout(tabs: List<HorizonTab>, selection: Int, showTabs: Boolean, select: (Int) -> Unit,
                         content: @Composable BoxScope.() -> Unit) {
    val palette = LocalHorizonPalette.current
    val supportsBlur = showTabs && Build.VERSION.SDK_INT >= 31
    val scene = if (supportsBlur) rememberGraphicsLayer() else null
    var contentOrigin by remember { mutableStateOf(Offset.Zero) }
    val capture = if (scene == null) Modifier else Modifier
        .onGloballyPositioned { contentOrigin = it.positionInRoot() }
        .drawWithContent {
            scene.record { this@drawWithContent.drawContent() }
            drawLayer(scene)
        }

    Box(Modifier.fillMaxSize().imePadding()) {
        Box(Modifier.fillMaxSize().then(capture).drawWithContent {
            drawRect(Brush.verticalGradient(listOf(palette.panel(0.15f), palette.background, palette.panel(0.05f))))
            drawRect(Brush.radialGradient(
                colors = listOf(palette.tint.copy(alpha = 0.31f), Color.Transparent),
                center = Offset(size.width * 0.84f, 0f),
                radius = maxOf(size.width, size.height * 0.54f),
            ))
            drawRect(Brush.radialGradient(
                colors = listOf(palette.tint.copy(alpha = 0.21f), Color.Transparent),
                center = Offset(0f, size.height * 0.94f),
                radius = maxOf(size.width, size.height * 0.46f),
            ))
            drawContent()
        }, content = content)
        if (showTabs) {
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                .widthIn(max = 300.dp).fillMaxWidth()) {
                GlassTabs(tabs, selection, scene, contentOrigin, select)
            }
        }
    }
}

@Composable
private fun GlassTabs(tabs: List<HorizonTab>, selection: Int, scene: GraphicsLayer?, contentOrigin: Offset,
                      select: (Int) -> Unit) {
    val palette = LocalHorizonPalette.current
    var glassOrigin by remember { mutableStateOf(Offset.Zero) }
    val blurRadius = with(LocalDensity.current) { 19.dp.toPx() }
    Box(
        Modifier.fillMaxWidth().onGloballyPositioned { glassOrigin = it.positionInRoot() }
            .shadow(20.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape),
    ) {
        if (scene != null) {
            Box(Modifier.matchParentSize().graphicsLayer {
                renderEffect = BlurEffect(blurRadius, blurRadius, TileMode.Clamp)
                clip = true
            }.drawWithContent {
                val offset = contentOrigin - glassOrigin
                translate(offset.x, offset.y) { drawLayer(scene) }
            })
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(
                    palette.panel(0.37f).copy(alpha = 0.76f), palette.panel(0.19f).copy(alpha = 0.88f),
                    palette.panel(0.13f).copy(alpha = 0.94f),
                )))
                .border(1.dp, palette.glassEdge(), CircleShape).padding(5.dp),
        ) {
            val slot = maxWidth / tabs.size
            val position by animateDpAsState(slot * selection,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
                label = "tab selection")
            Box(Modifier.align(Alignment.CenterStart).offset(x = position)
                .width(slot).height(51.dp).padding(horizontal = 3.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(
                    palette.tint.copy(alpha = 0.60f), palette.tint.copy(alpha = 0.22f),
                )))
                .border(1.dp, palette.glassEdge(), CircleShape))
            Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { index, tab ->
                    val selected = selection == index
                    val tint by animateColorAsState(if (selected) palette.link else palette.muted, label = "tab tint")
                    val scale by animateFloatAsState(if (selected) 1.12f else 1f,
                        animationSpec = spring(dampingRatio = 0.58f, stiffness = Spring.StiffnessMedium), label = "tab scale")
                    Box(Modifier.weight(1f).height(51.dp).clip(CircleShape)
                        .selectable(selected, role = Role.Tab, onClick = { select(index) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(tab.icon, stringResource(tab.label),
                            Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale }, tint = tint)
                    }
                }
            }
        }
    }
}

@Composable
internal fun Tile(modifier: Modifier = Modifier, accented: Boolean = false,
                  content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    val palette = LocalHorizonPalette.current
    val face = if (accented) listOf(palette.panel(0.42f), palette.panel(0.26f), palette.panel(0.16f))
        else listOf(palette.panel(0.16f), palette.panel(0.08f), palette.panel(0.04f))
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Brush.linearGradient(face))
            .border(0.8.dp, Brush.linearGradient(listOf(palette.link.copy(alpha = 0.23f), palette.tint.copy(alpha = 0.16f))), shape)
            .then(modifier).padding(horizontal = 17.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
        content = content,
    )
}

@Composable
internal fun GlassSettingRow(title: String, subtitle: String, enabled: Boolean = true, open: () -> Unit) {
    Tile(Modifier.clickable(enabled = enabled, role = Role.Button, onClick = open)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = LocalHorizonPalette.current.link)
        }
    }
}

@Composable
internal fun Modifier.glassPanel(shape: Shape, raised: Boolean = false): Modifier {
    val palette = LocalHorizonPalette.current
    return clip(shape)
        .background(Brush.verticalGradient(listOf(
            palette.panel(if (raised) 0.32f else 0.24f).copy(alpha = 0.93f),
            palette.panel(0.12f).copy(alpha = 0.96f),
            palette.panel(0.07f).copy(alpha = 0.98f),
        )))
        .border(1.dp, palette.glassEdge(), shape)
}

@Composable
internal fun GlassToolbarButton(title: String, onClick: () -> Unit) {
    Box(Modifier.glassPanel(CircleShape).clickable(role = Role.Button, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = 15.dp), contentAlignment = Alignment.Center) {
        Text(title, color = LocalHorizonPalette.current.text, style = MaterialTheme.typography.labelLarge)
    }
}
