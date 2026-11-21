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

internal fun Modifier.glassBackdrop(shape: Shape, radius: Dp, monochrome: Boolean = false): Modifier = composed {
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
                    if (monochrome) drawRect(Color(0xFF161616))
                    translate(shift.x, shift.y) { drawLayer(scene.layer) }
                }
                drawLayer(sampled)
                drawContent()
            }
        }
}

internal const val navigationSlideMillis = 220

internal fun Modifier.navigationGlass(shape: Shape): Modifier = composed {
    val graphite = Color(0xFF161616)
    val blur = backdropBlurAvailable
    glassBackdrop(shape, 22.dp, monochrome = true)
        .background(Brush.verticalGradient(listOf(
            if (blur) graphite.copy(alpha = .61f) else Color(0xFF242424),
            if (blur) graphite.copy(alpha = .75f) else graphite,
        )))
        .border(1.dp, Brush.verticalGradient(listOf(
            Color.White.copy(alpha = .31f), Color.White.copy(alpha = .14f),
        )), shape)
}

internal fun Modifier.frost(shape: Shape): Modifier = composed {
    val palette = LocalHorizonPalette.current
    val hardwareBlur = backdropBlurAvailable
    glassBackdrop(shape, 13.dp)
        .background(
            Brush.verticalGradient(
                listOf(
                    palette.elevated.copy(alpha = if (hardwareBlur) .55f else .85f),
                    palette.surface.copy(alpha = .78f),
                )
            )
        )
        .border(
            1.dp,
            Color.White.copy(alpha = .16f),
            shape,
        )
}
