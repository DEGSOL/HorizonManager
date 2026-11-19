package dev.horizonmanager

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider

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
    AlertDialog(onDismissRequest = onDismissRequest, title = {
        DialogMotion()
        title()
    }, text = text, confirmButton = confirmButton, dismissButton = dismissButton)
}
