package dev.horizonmanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal class NoticeQueue {
    val messages = SnackbarHostState()
    val dialogs = mutableStateListOf<Any>()
}

internal val LocalNoticeQueue = staticCompositionLocalOf<NoticeQueue?> { null }

@Composable
internal fun GlassNoticeHost(modifier: Modifier = Modifier, inDialog: Boolean = false) {
    val queue = LocalNoticeQueue.current ?: return
    val surface = if (inDialog) remember { Any() } else null
    if (surface != null) {
        DisposableEffect(queue, surface) {
            queue.dialogs.add(surface)
            onDispose { queue.dialogs.remove(surface) }
        }
    }
    if (queue.dialogs.lastOrNull() != surface) return

    SnackbarHost(
        queue.messages,
        modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        ).padding(start = 16.dp, end = 16.dp, top = 8.dp).widthIn(max = 520.dp),
    ) { message ->
        Text(
            message.visuals.message,
            Modifier.navigationGlass(RoundedCornerShape(22.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
        )
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
            GlassNoticeHost(Modifier.align(Alignment.TopCenter), inDialog = true)
        }
    }
}
