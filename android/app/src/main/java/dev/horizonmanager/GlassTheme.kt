package dev.horizonmanager

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ocean = Color(0xFF0B1423)
private val ice = Color(0xFF9CDBFF)
private val canvasTone = Color(0xFF080C16)
private val glassBorder = Brush.linearGradient(listOf(
    Color(0x8BE8F5FF), Color(0x2B96D8FF), Color(0x371C6FAE), Color(0x64C3DCFF),
))
private val tileEdge = Brush.linearGradient(listOf(Color(0x398DD4FC), Color(0x170B617D), Color(0x453D688D)))
private val tileFill = Brush.linearGradient(listOf(Color(0xEC16243A), Color(0xF4101B2C), Color(0xF40D1727)))

private val palette = darkColorScheme(
    primary = ice, onPrimary = Color(0xFF042136),
    primaryContainer = Color(0xFF1B3E60), onPrimaryContainer = Color(0xFFE0F3FF),
    secondary = Color(0xFFB5CFFF), secondaryContainer = Color(0xFF263955),
    onSecondaryContainer = Color(0xFFE4EEFF), tertiary = Color(0xFF8CE4DC),
    background = canvasTone, onBackground = Color(0xFFF2F7FD),
    surface = ocean, onSurface = Color(0xFFF2F7FD), onSurfaceVariant = Color(0xFFB6C9DD),
    surfaceVariant = Color(0xFF202D42), surfaceContainerLowest = canvasTone,
    surfaceContainerLow = Color(0xFF121D2E), surfaceContainer = Color(0xFF141F32),
    surfaceContainerHigh = Color(0xFF1A2940), surfaceContainerHighest = Color(0xFF243751),
    outline = Color(0xFF6D879F), outlineVariant = Color(0xFF344961), error = Color(0xFFFFACA9),
    errorContainer = Color(0xFF4C202C), onErrorContainer = Color(0xFFFFDDD9),
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
internal fun HorizonTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = palette, typography = textStyles, shapes = Shapes(
        extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(15.dp),
        medium = RoundedCornerShape(21.dp), large = RoundedCornerShape(27.dp),
        extraLarge = RoundedCornerShape(34.dp),
    ), content = content)
}

internal data class HorizonTab(@StringRes val label: Int, val icon: ImageVector)

@Composable
internal fun GlassLayout(tabs: List<HorizonTab>, selection: Int, showTabs: Boolean, select: (Int) -> Unit,
                         content: @Composable BoxScope.() -> Unit) {
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
            drawRect(Brush.verticalGradient(listOf(Color(0xFF101D31), canvasTone, Color(0xFF0B1322))))
            drawRect(Brush.radialGradient(
                colors = listOf(Color(0x58275080), Color.Transparent),
                center = Offset(size.width * 0.84f, 0f),
                radius = maxOf(size.width, size.height * 0.54f),
            ))
            drawRect(Brush.radialGradient(
                colors = listOf(Color(0x4B0D666B), Color.Transparent),
                center = Offset(0f, size.height * 0.94f),
                radius = maxOf(size.width, size.height * 0.46f),
            ))
            drawContent()
        }, content = content)
        if (showTabs) {
            Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                .widthIn(max = 490.dp).fillMaxWidth()) {
                GlassTabs(tabs, selection, scene, contentOrigin, select)
            }
        }
    }
}

@Composable
private fun GlassTabs(tabs: List<HorizonTab>, selection: Int, scene: GraphicsLayer?, contentOrigin: Offset,
                      select: (Int) -> Unit) {
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
        Row(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(
                    Color(0xC6375575), Color(0xE2182B47), Color(0xEE0E2139),
                )))
                .border(1.dp, glassBorder, CircleShape).selectableGroup().padding(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = selection == index
                val tint by animateColorAsState(if (selected) Color(0xFFF7FCFF) else Color(0xFFD0E1F1), label = "tab tint")
                val highlight by animateColorAsState(if (selected) Color(0x704F9ED6) else Color.Transparent, label = "tab glass")
                Column(
                    Modifier.weight(1f).heightIn(min = 57.dp).clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(highlight, highlight.copy(alpha = highlight.alpha * 0.42f))))
                        .then(if (selected) Modifier.border(1.dp, Brush.verticalGradient(listOf(
                            Color(0x8EBAE8FF), Color(0x4278B5E3), Color(0x36B7DBFF),
                        )), CircleShape) else Modifier)
                        .selectable(selected, role = Role.Tab, onClick = { select(index) })
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(tab.icon, null, Modifier.size(22.dp), tint = if (selected) ice else tint)
                    Text(stringResource(tab.label), color = tint, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun Tile(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier.fillMaxWidth().clip(shape).background(tileFill).border(0.8.dp, tileEdge, shape)
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
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun GlassToolbarButton(title: String, onClick: () -> Unit) {
    Text(title, color = Color(0xFFE9F7FF), style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0x704E7092), Color(0x501D3551))))
            .border(1.dp, glassBorder, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 13.dp))
}
