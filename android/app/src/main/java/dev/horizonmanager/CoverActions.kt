package dev.horizonmanager

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal data class CoverInk(val color: Color, val shadow: Shadow) {
    companion object {
        fun at(luminance: Float, radius: Float, offset: Float): CoverInk {
            val fraction = ((luminance - .04f) / .38f).coerceIn(0f, 1f)
            val blend = fraction * fraction * (3 - 2 * fraction)
            return CoverInk(
                if (luminance >= .179f) Color.Black else Color.White,
                Shadow(lerp(Color.White, Color.Black, blend).copy(alpha = .48f), Offset(0f, offset), radius),
            )
        }
    }
}

internal class CoverLuminance(private val width: Int, private val height: Int, pixels: IntArray) {
    private val tones = FloatArray(pixels.size) { Color(pixels[it]).compositeOver(Color.Black).luminance() }

    fun prefersBlack(frame: Size, center: Offset, diameter: Float, fit: Boolean = false): Boolean =
        average(frame, center, Size(diameter, diameter), fit) >= .179f

    fun average(frame: Size, center: Offset, area: Size, fit: Boolean = false): Float {
        if (frame.width <= 0f || frame.height <= 0f) return 0f
        val sx = frame.width / width
        val sy = frame.height / height
        val scale = if (fit) minOf(sx, sy) else maxOf(sx, sy)
        val left = (frame.width - width * scale) / 2
        val top = (frame.height - height * scale) / 2
        var sum = 0f
        for (y in -1..1) for (x in -1..1) {
            val px = (center.x + x * area.width / 3 - left) / scale
            val py = (center.y + y * area.height / 3 - top) / scale
            if (!fit || (px >= 0f && py >= 0f && px < width && py < height)) {
                sum += tones[py.toInt().coerceIn(0, height - 1) * width + px.toInt().coerceIn(0, width - 1)]
            }
        }
        return sum / 9f
    }
}

internal class CoverPlacement {
    var coordinates: LayoutCoordinates? = null
}

internal data class CoverActionSurface(
    val placement: CoverPlacement, val cover: Cover?, val photoHeight: Float? = null, val fit: Boolean = false,
)
internal val LocalCoverActionSurface = staticCompositionLocalOf<CoverActionSurface?> { null }

private data class CoverSample(val frame: Size, val center: Offset, val area: Size)

private fun CoverActionSurface?.sample(coordinates: LayoutCoordinates): CoverSample? {
    val parent = this?.placement?.coordinates ?: return null
    if (!parent.isAttached || !coordinates.isAttached) return null
    val area = Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())
    val center = parent.localPositionOf(coordinates, Offset(area.width / 2f, area.height / 2f))
    return CoverSample(Size(parent.size.width.toFloat(), minOf(parent.size.height.toFloat(),
        photoHeight ?: Float.MAX_VALUE)), center, area)
}

@Composable
internal fun AdaptiveCoverInk(content: @Composable (Modifier, CoverInk) -> Unit) {
    val surface = LocalCoverActionSurface.current
    val luminance = surface?.cover?.luminance
    var sample by remember { mutableStateOf<CoverSample?>(null) }
    val relative = sample?.let { luminance?.average(it.frame, it.center, it.area) }
    val ink = with(LocalDensity.current) {
        if (relative == null) CoverInk(Color.White, Shadow(Color.Black.copy(alpha = .52f),
            Offset(0f, 1.dp.toPx()), 4.dp.toPx()))
        else CoverInk.at(relative, 4.dp.toPx(), 1.dp.toPx())
    }
    content(Modifier.onPlaced { sample = surface.sample(it) }, ink)
}

@Composable
internal fun CoverIconAction(icon: ImageVector, description: String, active: Boolean? = null, onClick: () -> Unit) {
    val surface = LocalCoverActionSurface.current
    val diameter = with(LocalDensity.current) { 24.dp.toPx() }
    var sample by remember { mutableStateOf<CoverSample?>(null) }
    val black = remember(surface, sample, diameter) {
        sample?.let { surface?.cover?.luminance?.prefersBlack(it.frame, it.center, diameter, surface.fit) } == true
    }
    val touch = if (active == null) {
        Modifier.clickable(interactionSource = null, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier.toggleable(active, interactionSource = null, indication = null, role = Role.Checkbox) { onClick() }
    }
    Box(
        Modifier.size(48.dp).then(touch).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(40.dp).clip(CircleShape).onPlaced { sample = surface.sample(it) }) {
            val photo = surface?.cover
            val spot = sample
            if (surface != null && photo != null && spot != null) {
                translate(center.x - spot.center.x, center.y - spot.center.y) {
                    drawCoverImage(photo.soft, spot.frame, fit = surface.fit)
                }
            }
        }
        Icon(
            icon, contentDescription = null, modifier = Modifier.size(24.dp),
            tint = if (black) Color.Black else Color.White,
        )
    }
}
