package dev.horizonmanager

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun rememberUnreadBorderClock(hasVisibleUnread: () -> Boolean): State<Float>? {
    val visibleCheck by rememberUpdatedState(hasVisibleUnread)
    val visible by remember { derivedStateOf { visibleCheck() } }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (!visible || !lifecycle.isAtLeast(Lifecycle.State.RESUMED)) return null
    return rememberInfiniteTransition(label = "Unread outline").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9_000, easing = LinearEasing)),
        label = "Light along the outline",
    )
}

internal fun Modifier.unreadOutline(shape: RoundedCornerShape, articleId: String, opacity: State<Float>,
                                    clock: State<Float>?): Modifier = drawWithCache {
    val strokeWidth = .85.dp.toPx()
    if (size.width <= strokeWidth || size.height <= strokeWidth) return@drawWithCache onDrawWithContent { drawContent() }
    val inset = strokeWidth / 2
    val inner = Size(size.width - strokeWidth, size.height - strokeWidth)
    val corners = (shape.createOutline(inner, layoutDirection, this) as Outline.Rounded).roundRect
    val contour = Path().apply {
        addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset,
            corners.topLeftCornerRadius, corners.topRightCornerRadius, corners.bottomRightCornerRadius,
            corners.bottomLeftCornerRadius), Path.Direction.Clockwise)
    }
    val measure = PathMeasure().apply { setPath(contour, true) }
    val length = measure.length
    val stroke = Stroke(strokeWidth)
    val segment = Path()
    val segments = 16
    val step = length * .15f / segments
    val intensities = FloatArray(segments) { index ->
        val ramp = sin(PI * (index + .5) / segments).toFloat()
        .68f * ramp * ramp
    }
    val phase = (articleId.hashCode().ushr(1) % 997) / 997f

    onDrawWithContent {
        drawContent()
        val alpha = opacity.value
        if (alpha <= 0f || length <= 0f) return@onDrawWithContent
        drawPath(contour, Color.White, alpha = .28f * alpha, style = stroke)
        val time = clock?.value ?: return@onDrawWithContent
        val head = (time + phase).mod(1f) * length
        for (index in 0 until segments) {
            val start = (head + index * step) % length
            val end = start + step + strokeWidth * .2f
            segment.rewind()
            measure.getSegment(start, end.coerceAtMost(length), segment, true)
            if (end > length) measure.getSegment(0f, end - length, segment, true)
            drawPath(segment, Color.White, alpha = intensities[index] * alpha, style = stroke)
        }
    }
}
