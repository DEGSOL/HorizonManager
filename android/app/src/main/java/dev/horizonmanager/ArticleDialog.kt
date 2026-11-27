package dev.horizonmanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.LocalView
import org.json.JSONObject

@Composable
internal fun ArticleDialog(
    article: JSONObject,
    flag: ArticleFlag?,
    onSave: () -> Unit,
    onRead: () -> Unit,
    artworkServer: String,
    displayOptions: JSONObject,
    relay: (suspend () -> ByteArray?)? = null,
    contextContent: @Composable () -> Unit,
    close: () -> Unit,
) {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val theme = LocalHorizonPalette.current
    val bodyColor = Color(0xFF202020)
    val sourceLink = originalPublicationUrl(article)
    val link = article.optString("url")
    val classic = displayOptions.optString("card_text_mode", "classic") == "classic"
    val analyzed = article.optBoolean("summary_from_analysis")
    val summary = if (analyzed) articleField(article, "summary") else ""
    val body = if (classic) articleField(article, "body").ifBlank { articleField(article, "summary") } else summary
    val reason = if (analyzed && displayOptions.optBoolean("show_score_reason")) articleField(article, "reason") else ""
    var actions by remember { mutableStateOf(false) }
    val shareText =
        listOf(article.optString("title"), body, link.takeIf(::isWeb).orEmpty())
            .filter(String::isNotBlank)
            .joinToString("\n\n")

    val cover = artwork(artworkServer, article, relay)
    var showPhoto by rememberSaveable(article.optString("id")) { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.extraLarge
    ArticleModal(article.optString("title"), close, captureMenu = actions) {
        Column(
            Modifier.widthIn(max = 620.dp)
                .fillMaxWidth()
                .publicationGlassRim(shape)
                .clip(shape)
                .background(bodyColor)
                .pointerInput(Unit) { detectTapGestures {} }
                .verticalScroll(rememberScrollState())
        ) {
            PublicationCover(cover, article.optString("id"), openPhoto = { showPhoto = true }) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverIconAction(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            ui(R.string.ui_back),
                            onClick = close,
                        )
                        Spacer(Modifier.weight(1f))
                        if (sourceLink != null)
                            CoverIconAction(
                                ImageVector.vectorResource(R.drawable.ic_external_link),
                                ui(R.string.ui_original_source),
                            ) {
                                runCatching { uri.openUri(sourceLink) }
                            }
                        val saved = flag?.saved == true
                        CoverIconAction(
                            ImageVector.vectorResource(if (saved) R.drawable.ic_bookmark_filled
                                else R.drawable.ic_bookmark_outline),
                            ui(if (saved) R.string.remove_from_favorites else R.string.add_to_favorites),
                            active = saved,
                            onClick = onSave,
                        )
                        Box {
                            CoverIconAction(
                                Icons.Default.MoreVert,
                                ui(R.string.editorial_article_actions),
                            ) {
                                actions = true
                            }
                            GlassDropdownMenu(actions, { actions = false }) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            ui(
                                                if (flag?.read == true) R.string.mark_unread
                                                else R.string.ui_mark_as_read
                                            )
                                        )
                                    },
                                    onClick = {
                                        actions = false
                                        onRead()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(ui(R.string.ui_copy)) },
                                    onClick = {
                                        actions = false
                                        context
                                            .getSystemService(ClipboardManager::class.java)
                                            .setPrimaryClip(
                                                ClipData.newPlainText("Horizon", shareText)
                                            )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(ui(R.string.editorial_share)) },
                                    onClick = {
                                        actions = false
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND)
                                                    .setType("text/plain")
                                                    .putExtra(Intent.EXTRA_TEXT, shareText),
                                                null,
                                            )
                                        )
                                    },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(60.dp))
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        AdaptiveCoverInk { modifier, ink ->
                            Text(article.optString("title"), modifier,
                                style = MaterialTheme.typography.headlineMedium.copy(shadow = ink.shadow),
                                color = ink.color)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                PublicationSource(article, detailed = true)
                            }
                            PublicationScore(article, detailed = true)
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().background(bodyColor)
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Text(
                    localTime(articleField(article, "time")),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.muted,
                )
                when {
                    body.isBlank() -> Text(ui(R.string.article_no_analysis_text), color = theme.muted)
                    classic && !article.optBoolean("body_is_plain") -> Markdown(body)
                    else -> Text(linkedText(body), style = MaterialTheme.typography.bodyLarge)
                }
                if (reason.isNotBlank()) {
                    Tile {
                        Text(ui(R.string.article_score_reason), style = MaterialTheme.typography.titleSmall)
                        Text(reason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                contextContent()
                val media = article.optJSONObject("media")
                if (media != null && cover != null) {
                    val credit = listOf(articleField(media, "artist"), articleField(media, "license"))
                        .filter(String::isNotBlank).joinToString(" · ")
                    if (credit.isNotEmpty()) Text(credit, color = theme.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    if (showPhoto && cover != null) CoverViewer(cover, article.optString("title")) { showPhoto = false }
}

@Composable
private fun ArticleModal(
    title: String, close: () -> Unit, captureMenu: Boolean, content: @Composable BoxScope.() -> Unit,
) {
    Dialog(onDismissRequest = close, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        DialogMotion()
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        val dismissLabel = ui(R.string.ui_close)
        DialogNoticeSurface(Modifier.semantics { paneTitle = title }, captureMenu) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .45f)).clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = dismissLabel, onClick = close,
            ))
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = LocalNavigationClearance.current),
                contentAlignment = Alignment.Center,
                content = content,
            )
        }
    }
}
