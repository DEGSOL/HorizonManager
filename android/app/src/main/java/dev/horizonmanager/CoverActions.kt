package dev.horizonmanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal class CoverLuminance(private val width: Int, private val height: Int, pixels: IntArray) {
    private val tones = FloatArray(pixels.size) { Color(pixels[it]).compositeOver(Color.Black).luminance() }

    fun prefersBlack(frame: Size, center: Offset, diameter: Float): Boolean =
        average(frame, center, diameter) >= .179f

    fun average(frame: Size, center: Offset, diameter: Float): Float {
        if (frame.width <= 0f || frame.height <= 0f) return 0f
        val scale = maxOf(frame.width / width, frame.height / height)
        val left = (frame.width - width * scale) / 2
        val top = (frame.height - height * scale) / 2
        var sum = 0f
        for (y in -1..1) for (x in -1..1) {
            val px = ((center.x + x * diameter / 3 - left) / scale).toInt().coerceIn(0, width - 1)
            val py = ((center.y + y * diameter / 3 - top) / scale).toInt().coerceIn(0, height - 1)
            sum += tones[py * width + px]
        }
        // At this relative luminance, white and black have equal WCAG contrast ratios.
        return sum / 9f
    }
}

internal class CoverPlacement {
    var coordinates: LayoutCoordinates? = null
}

internal data class CoverActionSurface(val placement: CoverPlacement, val luminance: CoverLuminance?, val photoHeight: Float?)
internal val LocalCoverActionSurface = staticCompositionLocalOf<CoverActionSurface?> { null }

private data class GlyphSample(val frame: Size, val center: Offset)

@Composable
internal fun CoverIconAction(icon: ImageVector, description: String, active: Boolean? = null, onClick: () -> Unit) {
    val surface = LocalCoverActionSurface.current
    val diameter = with(LocalDensity.current) { 24.dp.toPx() }
    var sample by remember { mutableStateOf<GlyphSample?>(null) }
    val black = remember(surface, sample, diameter) {
        sample?.let { surface?.luminance?.prefersBlack(it.frame, it.center, diameter) } == true
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
        Icon(
            icon, contentDescription = null,
            modifier = Modifier.size(24.dp).onPlaced { coordinates ->
                val parent = surface?.placement?.coordinates
                if (parent?.isAttached == true && coordinates.isAttached) {
                    val position = parent.localPositionOf(
                        coordinates, Offset(coordinates.size.width / 2f, coordinates.size.height / 2f),
                    )
                    val height = minOf(parent.size.height.toFloat(), surface.photoHeight ?: Float.MAX_VALUE)
                    sample = GlyphSample(Size(parent.size.width.toFloat(), height), position)
                }
            },
            tint = if (black) Color.Black else Color.White,
        )
    }
}
