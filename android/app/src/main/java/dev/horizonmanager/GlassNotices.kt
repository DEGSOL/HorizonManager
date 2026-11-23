package dev.horizonmanager

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class SavedNotice(override val message: String) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction = false
    override val duration = SnackbarDuration.Short
}

internal class NoticeQueue {
    val messages = SnackbarHostState()
    val dialogs = mutableStateListOf<Any>()
}

internal val LocalNoticeQueue = staticCompositionLocalOf<NoticeQueue?> { null }

@Composable
internal fun BoxScope.GlassNoticeHost(bottomClearance: Dp = defaultNavigationClearance, inDialog: Boolean = false) {
    val queue = LocalNoticeQueue.current ?: return
    val surface = if (inDialog) remember { Any() } else null
    if (surface != null) {
        DisposableEffect(queue, surface) {
            queue.dialogs.add(surface)
            onDispose { queue.dialogs.remove(surface) }
        }
    }
    if (queue.dialogs.lastOrNull() != surface) return

    val current = queue.messages.currentSnackbarData
    var lastWasSaved by remember { mutableStateOf(false) }
    val atBottom = if (current == null) lastWasSaved else current.visuals is SavedNotice
    SideEffect { if (current != null) lastWasSaved = atBottom }
    val insets = WindowInsetsSides.Horizontal + if (atBottom) WindowInsetsSides.Bottom else WindowInsetsSides.Top
    SnackbarHost(
        queue.messages,
        Modifier.align(if (atBottom) Alignment.BottomCenter else Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(insets))
            .padding(start = 16.dp, end = 16.dp, top = if (atBottom) 0.dp else 8.dp,
                bottom = if (atBottom) bottomClearance else 0.dp)
            .widthIn(max = 520.dp),
    ) { message ->
        val wash = if (message.visuals is SavedNotice) savedNoticeWash() else Modifier
        Text(
            message.visuals.message,
            Modifier.navigationGlass(RoundedCornerShape(22.dp))
                .then(wash)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun savedNoticeWash(): Modifier {
    val phase = rememberInfiniteTransition(label = "Saved notice background").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "Color movement",
    )
    return Modifier.drawWithCache {
        val radius = maxOf(size.width * .65f, size.height * 1.5f)
        val violet = Brush.radialGradient(
            listOf(Color(0xFF8E76DB).copy(alpha = .37f), Color.Transparent), Offset.Zero, radius,
        )
        val teal = Brush.radialGradient(
            listOf(Color(0xFF45A9BA).copy(alpha = .34f), Color.Transparent), Offset.Zero, radius,
        )
        val amber = Brush.radialGradient(
            listOf(Color(0xFFD69768).copy(alpha = .25f), Color.Transparent), Offset.Zero, radius,
        )
        onDrawWithContent {
            val position = phase.value
            translate(size.width * (.10f + position * .55f), size.height * .17f) {
                drawCircle(violet, radius, Offset.Zero)
            }
            translate(size.width * (.86f - position * .36f), size.height * .94f) {
                drawCircle(teal, radius, Offset.Zero)
            }
            translate(size.width * (1f - position * .80f), size.height * 1.24f) {
                drawCircle(amber, radius, Offset.Zero)
            }
            drawContent()
        }
    }
}

@Composable
internal fun DialogNoticeSurface(
    modifier: Modifier = Modifier, captureMenu: Boolean = false, content: @Composable BoxScope.() -> Unit,
) {
    val layer = rememberGraphicsLayer()
    val scene = remember(layer) { GlassScene(layer) }
    val hasNotice = LocalNoticeQueue.current?.messages?.currentSnackbarData != null
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (hasNotice || captureMenu) Modifier.recordScene(scene) else Modifier)) {
            CompositionLocalProvider(LocalMenuScene provides scene) { content() }
        }
        CompositionLocalProvider(LocalGlassScene provides scene) {
            GlassNoticeHost(inDialog = true)
        }
    }
}
