package dev.horizonmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
internal fun AgentUpdateOffer(installedVersion: String, dismiss: () -> Unit, update: () -> Unit) {
    UpdateOffer(
        ui(R.string.ui_update_vps_agent), ui(R.string.hovq_agent_update_offer, installedVersion, BuildConfig.AGENT_VERSION),
        ui(R.string.hovq_update_now), dismiss, update,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UpdateOffer(title: String, message: String, action: String, dismiss: () -> Unit, update: () -> Unit) {
    val palette = LocalHorizonPalette.current
    Dialog(onDismissRequest = dismiss) {
        DialogMotion()
        CompositionLocalProvider(LocalGlassScene provides LocalMenuScene.current) {
            Column(
                Modifier.widthIn(max = 420.dp).fillMaxWidth()
                    .glassSurface(MaterialTheme.shapes.extraLarge, backdrop = true, menu = true)
                    .semantics { paneTitle = title }.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = palette.text)
                Text(message, style = MaterialTheme.typography.bodyMedium, color = palette.text)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MaterialTextButton(
                        onClick = dismiss, shape = feedGlassShape,
                        modifier = Modifier.glassSurface(feedGlassShape, raised = true, outlined = false),
                        colors = ButtonDefaults.textButtonColors(contentColor = palette.muted),
                    ) { Text(ui(R.string.hovq_later)) }
                    MaterialTextButton(
                        onClick = update, shape = feedGlassShape,
                        modifier = Modifier.readingNavigationGlass(feedGlassShape, menu = true),
                        colors = ButtonDefaults.textButtonColors(contentColor = ReadingGlass.text),
                    ) { Text(action) }
                }
            }
        }
    }
}
