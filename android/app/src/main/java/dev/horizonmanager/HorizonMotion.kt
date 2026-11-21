package dev.horizonmanager

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.PI
import kotlin.math.sin

@Composable
internal fun Reveal(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(
        visible,
        enter = fadeIn(tween(180)) + expandVertically(tween(230)),
        exit = fadeOut(tween(130)) + shrinkVertically(tween(200)),
        content = { Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content) },
    )
}

@Composable
internal fun DialogMotion() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        if (window != null && window.attributes.windowAnimations != R.style.HorizonDialogMotion) {
            window.setWindowAnimations(R.style.HorizonDialogMotion)
        }
    }
}

@Composable
internal fun HorizonAlertDialog(onDismissRequest: () -> Unit, title: @Composable () -> Unit,
                                text: @Composable () -> Unit, confirmButton: @Composable () -> Unit,
                                dismissButton: (@Composable () -> Unit)? = null) {
    val shape = RoundedCornerShape(28.dp)
    CompositionLocalProvider(LocalGlassScene provides LocalMenuScene.current) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            modifier = Modifier.glassSurface(shape, backdrop = true, menu = true),
            shape = shape, containerColor = Color.Transparent, tonalElevation = 0.dp,
            title = { DialogMotion(); title() }, text = text,
            confirmButton = confirmButton, dismissButton = dismissButton,
        )
    }
}

@Composable
internal fun DottedProgress(modifier: Modifier = Modifier, dots: Int = 13) {
    val color = LocalHorizonPalette.current.text
    val clock = rememberInfiniteTransition(label = "Loading dots").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "Dot movement",
    )
    Canvas(modifier.height(12.dp).progressSemantics()) {
        for (index in 0 until dots) {
            val phase = (index.toFloat() / dots + clock.value * (if (dots > 6) 4f else 2f) / dots).mod(1f)
            val lift = (1f + sin(2 * PI * (phase * 3f - clock.value)).toFloat()) * .5f
            val edge = minOf(phase * 8f, (1f - phase) * 8f, 1f)
            drawCircle(color, radius = (.8f + 1.25f * lift).dp.toPx(),
                center = Offset(phase * size.width, size.height * (.80f - .6f * lift)),
                alpha = edge * (.25f + .62f * lift))
        }
    }
}
