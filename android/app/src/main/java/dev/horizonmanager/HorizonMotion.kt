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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

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
                                dismissButton: (@Composable () -> Unit)? = null, light: Boolean = LocalSettingsGlass.current) {
    val shape = if (light) feedGlassShape else RoundedCornerShape(28.dp)
    val surface = if (light) Modifier.readingNavigationGlass(shape, menu = true)
        else Modifier.glassSurface(shape, backdrop = true, menu = true)
    val colors = MaterialTheme.colorScheme
    CompositionLocalProvider(LocalGlassScene provides LocalMenuScene.current) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            modifier = surface,
            shape = shape, containerColor = Color.Transparent, tonalElevation = 0.dp,
            titleContentColor = if (light) ReadingGlass.text else colors.onSurface,
            textContentColor = if (light) ReadingGlass.muted else colors.onSurfaceVariant,
            title = { DialogMotion(); title() }, text = text,
            confirmButton = confirmButton, dismissButton = dismissButton,
        )
    }
}

private data class DotMotion(val phase: Float, val turns: Int, val amplitude: Float)

@Composable
internal fun DottedProgress(modifier: Modifier = Modifier, dots: Int = 13) {
    val color = if (LocalLightGlass.current) ReadingGlass.text else LocalHorizonPalette.current.text
    val wobble = remember(dots) {
        val random = Random(dots)
        List(dots) { DotMotion(random.nextFloat(), random.nextInt(6, 16), .08f + random.nextFloat() * .20f) }
    }
    val clock = rememberInfiniteTransition(label = "Loading dots").animateFloat(
        0f, 1f, infiniteRepeatable(tween(7800, easing = LinearEasing)), label = "Dot movement",
    )
    Canvas(modifier.height(12.dp).progressSemantics()) {
        val time = clock.value
        for (index in wobble.indices) {
            val dot = wobble[index]
            val position = (index.toFloat() / dots + time).mod(1f)
            val lift = sin(2 * PI * (dot.phase + time * dot.turns)).toFloat()
            val edge = minOf(position * 8f, (1f - position) * 8f, 1f)
            drawCircle(color, radius = (1.45f + .38f * lift).dp.toPx(),
                center = Offset(position * size.width, size.height * (.5f + lift * dot.amplitude)),
                alpha = edge * (.56f + .25f * lift))
        }
    }
}
