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

internal fun Modifier.frost(shape: Shape, active: Boolean = false): Modifier = composed {
    val scene = LocalGlassScene.current
    val palette = LocalHorizonPalette.current
    var position by remember { mutableStateOf(Offset.Zero) }
    val hardwareBlur = scene != null && Build.VERSION.SDK_INT >= 31
    this.onGloballyPositioned { position = it.positionInRoot() }
        .clip(shape)
        .then(
            if (hardwareBlur)
                Modifier.drawWithCache {
                    val sampled =
                        obtainGraphicsLayer().apply {
                            renderEffect = BlurEffect(13.dp.toPx(), 13.dp.toPx(), TileMode.Clamp)
                        }
                    onDrawWithContent {
                        val shift = scene!!.origin - position
                        sampled.record { translate(shift.x, shift.y) { drawLayer(scene.layer) } }
                        drawLayer(sampled)
                        drawContent()
                    }
                }
            else Modifier
        )
        .background(
            Brush.verticalGradient(
                listOf(
                    (if (active) palette.tint else palette.elevated).copy(
                        alpha = if (hardwareBlur) .55f else .85f
                    ),
                    palette.surface.copy(alpha = .78f),
                )
            )
        )
        .border(
            1.dp,
            (if (active) palette.tint else Color.White).copy(alpha = if (active) .55f else .16f),
            shape,
        )
}
