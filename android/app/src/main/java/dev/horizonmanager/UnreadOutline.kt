package dev.horizonmanager

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.random.Random

internal data class BorderWaveFrame(val a: Float, val b: Float, val c: Float)

@Composable
internal fun rememberUnreadBorderClock(hasVisibleUnread: () -> Boolean): State<BorderWaveFrame> {
    val visibleCheck by rememberUpdatedState(hasVisibleUnread)
    val visible by remember { derivedStateOf { visibleCheck() } }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = visible && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var animated by remember(active) { mutableStateOf(false) }
    if (active) LaunchedEffect(Unit) {
        delay(navigationSlideMillis.toLong())
        animated = true
    }
    val animation = if (animated) rememberInfiniteTransition(label = "Unread outline").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(10_000, easing = LinearEasing)),
        label = "Intensity of the waves",
    ) else null
    val phase = rememberUpdatedState(animation)
    // Keep the draw cache when animation starts. The shared clock bounds redraws to 30 Hz.
    return remember {
        derivedStateOf(androidx.compose.runtime.structuralEqualityPolicy()) {
            val step = ((phase.value?.value ?: return@derivedStateOf restingWaves) * 300).toInt()
            val angle = step * (2 * PI / 300)
            BorderWaveFrame(
                sin(3 * angle + .2).toFloat(),
                sin(5 * angle + 1.9).toFloat(),
                sin(7 * angle + 4.1).toFloat(),
            )
        }
    }
}

private val restingWaves = BorderWaveFrame(sin(.2).toFloat(), sin(1.9).toFloat(), sin(4.1).toFloat())
internal fun Modifier.unreadOutline(
    shape: RoundedCornerShape, articleId: String, opacity: State<Float>, clock: State<BorderWaveFrame>?,
): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this) as? Outline.Rounded
        ?: return@drawWithCache onDrawBehind {}
    val outer = Path().apply { addRoundRect(outline.roundRect, Path.Direction.Clockwise) }
    val measure = PathMeasure().apply { setPath(outer, true) }
    val length = measure.length
    if (length <= 0f) return@drawWithCache onDrawBehind {}

    val count = ceil(length / 4.dp.toPx()).toInt().coerceIn(64, 416)
    val x = FloatArray(count)
    val y = FloatArray(count)
    val inwardX = FloatArray(count)
    val inwardY = FloatArray(count)
    val waveA = FloatArray(count)
    val waveB = FloatArray(count)
    val waveC = FloatArray(count)
    val random = Random(articleId.hashCode())
    val phaseA = random.nextDouble(2 * PI)
    val phaseB = random.nextDouble(2 * PI)
    val phaseC = random.nextDouble(2 * PI)
    for (i in 0 until count) {
        val fraction = i.toFloat() / count
        val position = measure.getPosition(fraction * length)
        val tangent = measure.getTangent(fraction * length)
        x[i] = position.x
        y[i] = position.y
        inwardX[i] = -tangent.y
        inwardY[i] = tangent.x
        val angle = fraction * 2 * PI
        waveA[i] = sin(3 * angle + phaseA).toFloat()
        waveB[i] = sin(5 * angle + phaseB).toFloat()
        waveC[i] = sin(8 * angle + phaseC).toFloat()
    }
    val border = Path()
    val minimum = 1.55.dp.toPx()
    val amplitude = 5.1.dp.toPx()
    val maxWidth = size.minDimension / 4
    var lastFrame: BorderWaveFrame? = null

    onDrawBehind {
        val alpha = opacity.value
        if (alpha <= 0f) return@onDrawBehind
        val frame = clock?.value ?: restingWaves
        if (frame != lastFrame) {
            border.rewind()
            border.fillType = PathFillType.EvenOdd
            // The outer edge is the very same outline the tile uses for clipping.
            border.addPath(outer)
            for (i in 0 until count) {
                val light = (.57f + .30f * waveA[i] * frame.a +
                    .20f * waveB[i] * frame.b + .12f * waveC[i] * frame.c).coerceIn(0f, 1f)
                val width = (minimum + amplitude * light * light).coerceAtMost(maxWidth)
                val px = x[i] + inwardX[i] * width
                val py = y[i] + inwardY[i] * width
                if (i == 0) border.moveTo(px, py) else border.lineTo(px, py)
            }
            border.close()
            lastFrame = frame
        }
        drawPath(border, Color.White, alpha = .88f * alpha)
    }
}
