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
    var originOnScreen = Offset.Unspecified
}

internal val LocalGlassScene = compositionLocalOf<GlassScene?> { null }
internal val LocalMenuScene = compositionLocalOf<GlassScene?> { null }

// Capture only the underlying content. Sampling siblings avoids recursive glass layers.
internal fun Modifier.recordScene(scene: GlassScene): Modifier =
    onGloballyPositioned {
        scene.origin = it.positionInRoot()
        scene.originOnScreen = it.localToScreen(Offset.Zero)
    }
        .drawWithContent {
            scene.layer.record { this@drawWithContent.drawContent() }
            drawLayer(scene.layer)
        }

internal val backdropBlurAvailable: Boolean
    @Composable get() = LocalGlassScene.current != null && Build.VERSION.SDK_INT >= 31

private fun Modifier.glassBackdrop(
    shape: Shape, radius: Dp, base: Color, monochrome: Boolean, menu: Boolean, tintStrength: Float,
): Modifier = composed {
    val scene = LocalGlassScene.current
    if (scene == null || Build.VERSION.SDK_INT < 31) return@composed clip(shape)
    var position by remember { mutableStateOf(if (menu) Offset.Unspecified else Offset.Zero) }
    this.onGloballyPositioned { position = if (menu) it.localToScreen(Offset.Zero) else it.positionInRoot() }
        .clip(shape)
        .drawWithCache {
            val blur = radius.toPx()
            val sampled = obtainGraphicsLayer().apply {
                renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
                if (monochrome) colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
            }
            onDrawWithContent {
                val origin = if (menu) scene.originOnScreen else scene.origin
                sampled.record {
                    drawRect(base)
                    if (origin != Offset.Unspecified && position != Offset.Unspecified) {
                        val shift = origin - position
                        translate(shift.x, shift.y) { drawLayer(scene.layer) }
                    }
                    if (tintStrength > 0f) drawRect(base, alpha = tintStrength)
                }
                drawLayer(sampled)
                drawContent()
            }
        }
}

internal const val navigationSlideMillis = 220


internal fun Modifier.navigationGlass(shape: Shape, outlined: Boolean = true): Modifier =
    glassSurface(shape, backdrop = true, outlined = outlined)

internal object ReadingGlass {
    val base = Color(0xFFE0E0E0)
    val raised = Color(0xFFF4F4F4)
    val text = Color(0xFF191919)
    val muted = Color(0xFF3C3C3C)
}

private val readingBarBase = Color(0xFFBBBBBB)
private val readingBarTop = Color(0xFFC4C4C4)

internal fun Modifier.readingNavigationGlass(shape: Shape): Modifier = composed {
    // The gray tint keeps the bars close in tone over both dark and light covers.
    glassMaterial(shape, backdropBlurAvailable, readingBarBase, readingBarTop, outlined = true, tintStrength = .82f)
}

internal const val statusGlassFadeFraction = .47f

@Composable
internal fun BoxScope.StatusGlass(extendIntoFeed: Boolean = false) {
    val inset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    if (inset <= 0.dp) return

    // Only the feed lets content scroll through the fade below the system bar.
    val height = if (extendIntoFeed) inset * (1f + statusGlassFadeFraction) else inset
    val fadeStart = if (extendIntoFeed) 1f / (1f + statusGlassFadeFraction) else .82f
    Spacer(Modifier.align(androidx.compose.ui.Alignment.TopCenter).fillMaxWidth()
        .height(height).statusBarGlass(fadeStart))
}

internal fun Modifier.glassRim(shape: Shape, edge: Color = Color.White): Modifier =
    border(1.dp, Brush.verticalGradient(listOf(edge.copy(alpha = .29f), edge.copy(alpha = .12f))), shape)

private val publicationEdgeLight = Brush.linearGradient(
    0f to Color.White.copy(alpha = .47f),
    .28f to Color.White.copy(alpha = .24f),
    .66f to Color.White.copy(alpha = .12f),
    1f to Color.White.copy(alpha = .28f),
)

internal fun Modifier.publicationGlassRim(shape: Shape): Modifier =
    border(.85.dp, publicationEdgeLight, shape)

private fun Modifier.statusBarGlass(fadeStart: Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val fade = Brush.verticalGradient(fadeStart to Color.Black, 1f to Color.Transparent)
            onDrawWithContent {
                drawContent()
                drawRect(fade, blendMode = BlendMode.DstIn)
            }
        }
        .navigationGlass(RectangleShape, outlined = false)

internal fun Modifier.glassSurface(
    shape: Shape, backdrop: Boolean = false, raised: Boolean = false, outlined: Boolean = true, menu: Boolean = false,
    lightNavigation: Boolean = false,
): Modifier = composed {
    val palette = LocalHorizonPalette.current
    val blur = backdrop && backdropBlurAvailable
    val base = if (lightNavigation) ReadingGlass.base else palette.glassBase
    val top = if (lightNavigation) ReadingGlass.raised else if (raised) palette.glassSelection else palette.glassRaised
    glassMaterial(shape, blur, base, top, outlined, menu)
}

private fun Modifier.glassMaterial(
    shape: Shape, blur: Boolean, base: Color, top: Color, outlined: Boolean,
    menu: Boolean = false, tintStrength: Float = 0f,
): Modifier {
    val surface = if (blur) glassBackdrop(shape, 22.dp, base, monochrome = true, menu = menu,
        tintStrength = tintStrength) else clip(shape)
    return surface.background(Brush.verticalGradient(listOf(
        top.copy(alpha = if (blur) .66f else .94f),
        base.copy(alpha = if (blur) .77f else .92f),
    ))).then(if (outlined) Modifier.glassRim(shape, Color.White) else Modifier)
}
