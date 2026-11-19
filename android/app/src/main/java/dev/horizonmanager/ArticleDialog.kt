package dev.horizonmanager

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.JSONObject

@Composable
internal fun ArticleDialog(article: JSONObject, close: () -> Unit) {
    val uri = LocalUriHandler.current
    val theme = LocalHorizonPalette.current
    val link = article.optString("url")
    val body = article.optString("body").ifBlank { article.optString("summary") }
    val blocks = article.optJSONArray("blocks")?.objects()?.filterNot { it.optBoolean("primary") }.orEmpty()
    val citations = article.optJSONArray("citations")?.objects().orEmpty().filter { isWeb(it.optString("url")) }
    val reason = article.optString("reason").takeIf { it != "null" }.orEmpty()
    var comments by remember(article.optString("id")) { mutableStateOf(false) }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DialogMotion()
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 670.dp).fillMaxWidth().heightIn(max = maxHeight * 0.88f)
                .glassPanel(MaterialTheme.shapes.extraLarge, raised = true)) {
                Row(Modifier.align(Alignment.End).padding(top = 12.dp, end = 14.dp).glassPanel(CircleShape),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (isWeb(link)) GlassIconAction(ImageVector.vectorResource(R.drawable.ic_external_link),
                        ui(R.string.ui_original_source)) { runCatching { uri.openUri(link) } }
                    GlassIconAction(Icons.Default.Close, ui(R.string.ui_close), onClick = close)
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(start = 22.dp, end = 22.dp, top = 13.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(15.dp)) {
                    Text(article.optString("title"), style = MaterialTheme.typography.headlineSmall, color = theme.text)
                    if (article.optBoolean("body_is_plain")) Text(body, style = MaterialTheme.typography.bodyLarge)
                    else Markdown(body)
                    if (reason.isNotBlank() || blocks.isNotEmpty() || citations.isNotEmpty()) {
                        TextButton(onClick = { comments = !comments }) {
                            Text(if (comments) ui(R.string.ui_hide_horizon_commentary) else ui(R.string.ui_horizon_commentary))
                        }
                    }
                    Reveal(comments) {
                        if (reason.isNotBlank()) {
                            Text(ui(R.string.ui_horizon_assessment), style = MaterialTheme.typography.titleMedium)
                            Markdown(reason)
                        }
                        blocks.forEach { block ->
                            Text(block.optString("title"), style = MaterialTheme.typography.titleMedium)
                            Markdown(block.optString("content"))
                        }
                        citations.forEach { citation ->
                            val url = citation.optString("url")
                            TextButton(onClick = { runCatching { uri.openUri(url) } }) {
                                Text(citation.optString("title").ifBlank { url }, color = theme.link)
                            }
                        }
                    }
                }
            }
        }
    }
}
