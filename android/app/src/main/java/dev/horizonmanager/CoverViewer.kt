package dev.horizonmanager

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal data class PhotoViewport(val zoom: Float = 1f, val offset: Offset = Offset.Zero) {
    fun transform(centroid: Offset, pan: Offset, ratio: Float, frame: IntSize, photo: IntSize): PhotoViewport {
        val scale = (zoom * ratio).coerceIn(1f, 5f)
        if (scale == 1f) return PhotoViewport()
        val fit = minOf(frame.width.toFloat() / photo.width, frame.height.toFloat() / photo.height)
        val bound = Size(
            ((photo.width * fit * scale - frame.width) / 2).coerceAtLeast(0f),
            ((photo.height * fit * scale - frame.height) / 2).coerceAtLeast(0f),
        )
        val pivot = centroid - Offset(frame.width / 2f, frame.height / 2f)
        val next = pivot + (offset - pivot) * (scale / zoom) + pan
        return PhotoViewport(scale, Offset(next.x.coerceIn(-bound.width, bound.width),
            next.y.coerceIn(-bound.height, bound.height)))
    }
}

@Composable
internal fun CoverViewer(cover: Cover, description: String, close: () -> Unit) {
    val placement = remember { CoverPlacement() }
    val viewport = remember(cover) { mutableStateOf(PhotoViewport()) }
    val photoSize = IntSize(cover.image.width, cover.image.height)
    val surface = remember(cover) { CoverActionSurface(placement, cover, fit = true, viewport = viewport) }
    Dialog(onDismissRequest = close, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        DialogMotion()
        Box(Modifier.fillMaxSize().clipToBounds().background(Color.Black)
            .onPlaced { placement.coordinates = it }) {
            Box(Modifier.matchParentSize()
                .pointerInput(photoSize) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        viewport.value = viewport.value.transform(centroid, pan, zoom, size, photoSize)
                    }
                }
                .pointerInput(photoSize) {
                    detectTapGestures(onDoubleTap = { position ->
                        viewport.value = if (viewport.value.zoom > 1f) PhotoViewport()
                            else viewport.value.transform(position, Offset.Zero, 2.5f, size, photoSize)
                    })
                }) {
                Image(cover.image, description, Modifier.matchParentSize().graphicsLayer {
                    val transform = viewport.value
                    scaleX = transform.zoom
                    scaleY = transform.zoom
                    translationX = transform.offset.x
                    translationY = transform.offset.y
                }, contentScale = ContentScale.Fit)
            }
            Box(Modifier.align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(12.dp)) {
                CompositionLocalProvider(LocalCoverActionSurface provides surface) {
                    CoverIconAction(Icons.AutoMirrored.Filled.ArrowBack, ui(R.string.ui_back), onClick = close)
                }
            }
        }
    }
}
