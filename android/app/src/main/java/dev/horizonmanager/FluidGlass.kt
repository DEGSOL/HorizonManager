package dev.horizonmanager

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal class GlassScene(val layer: GraphicsLayer) {
    var origin = Offset.Zero
}

internal val LocalGlassScene = compositionLocalOf<GlassScene?> { null }

// Capture only the underlying content. Sampling siblings avoids recursive glass layers.
internal fun Modifier.recordScene(scene: GlassScene): Modifier =
    onGloballyPositioned { scene.origin = it.positionInRoot() }
        .drawWithContent {
            scene.layer.record { this@drawWithContent.drawContent() }
            drawLayer(scene.layer)
        }

internal val backdropBlurAvailable: Boolean
    @Composable get() = LocalGlassScene.current != null && Build.VERSION.SDK_INT >= 31

private fun Modifier.glassBackdrop(shape: Shape, radius: Dp, base: Color, monochrome: Boolean): Modifier = composed {
    val scene = LocalGlassScene.current
    if (scene == null || Build.VERSION.SDK_INT < 31) return@composed clip(shape)
    var position by remember { mutableStateOf(Offset.Zero) }
    this.onGloballyPositioned { position = it.positionInRoot() }
        .clip(shape)
        .drawWithCache {
            val blur = radius.toPx()
            val sampled = obtainGraphicsLayer().apply {
                renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
                if (monochrome) colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            }
            onDrawWithContent {
                val shift = scene.origin - position
                sampled.record {
                    drawRect(base)
                    translate(shift.x, shift.y) { drawLayer(scene.layer) }
                }
                drawLayer(sampled)
                drawContent()
            }
        }
}

internal const val navigationSlideMillis = 220

internal val ThemePreset.glassBase: Color
    get() = if (this == ThemePreset.Graphite) Color(0xFF161616) else surface

internal val ThemePreset.glassRaised: Color
    get() = if (this == ThemePreset.Graphite) Color(0xFF242424) else card

internal val ThemePreset.glassSelection: Color
    get() = if (this == ThemePreset.Graphite) Color(0xFF414141) else lerp(elevated, tint, .20f)

internal fun Modifier.navigationGlass(shape: Shape, outlined: Boolean = true): Modifier =
    glassSurface(shape, backdrop = true, outlined = outlined)

internal fun Modifier.glassSurface(
    shape: Shape, backdrop: Boolean = false, raised: Boolean = false, outlined: Boolean = true,
): Modifier = composed {
    val palette = LocalHorizonPalette.current
    val blur = backdrop && backdropBlurAvailable
    val graphite = palette == ThemePreset.Graphite
    val base = palette.glassBase
    val top = if (raised) palette.glassSelection else palette.glassRaised
    val edge = if (graphite) Color.White else palette.text
    val surface = if (blur) glassBackdrop(shape, 22.dp, base, monochrome = graphite) else clip(shape)
    surface.background(Brush.verticalGradient(listOf(
        top.copy(alpha = if (blur) .66f else .94f),
        base.copy(alpha = if (blur) .77f else .92f),
    ))).then(if (outlined) Modifier.border(1.dp, Brush.verticalGradient(listOf(
        edge.copy(alpha = .29f), edge.copy(alpha = .12f),
    )), shape) else Modifier)
}
