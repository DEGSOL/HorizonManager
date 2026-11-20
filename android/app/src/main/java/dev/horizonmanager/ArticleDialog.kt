package dev.horizonmanager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
internal fun ArticleDialog(
    article: JSONObject,
    flag: ArticleFlag?,
    onSave: () -> Unit,
    onRead: () -> Unit,
    artworkServer: String,
    relay: (suspend () -> ByteArray?)? = null,
    contextContent: @Composable () -> Unit,
    close: () -> Unit,
) {
    val uri = LocalUriHandler.current
    val context = LocalContext.current
    val theme = LocalHorizonPalette.current
    val link = article.optString("url")
    val body = article.optString("body").ifBlank { article.optString("summary") }
    var actions by remember { mutableStateOf(false) }
    val shareText =
        listOf(article.optString("title"), body, link.takeIf(::isWeb).orEmpty())
            .filter(String::isNotBlank)
            .joinToString("\n\n")

    BackHandler { close() }
    val cover = artwork(artworkServer, article, relay)
    var titleHeight by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = .25f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = close,
            )
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 78.dp)
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 620.dp)
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraLarge)
                .background(theme.surface)
                .pointerInput(Unit) { detectTapGestures {} }
                .verticalScroll(rememberScrollState())
        ) {
            Box {
                CoverSurface(
                    cover,
                    article.optString("id"),
                    titleHeight,
                    Modifier.matchParentSize(),
                )
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlassIconAction(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            ui(R.string.ui_back),
                            onClick = close,
                        )
                        Spacer(Modifier.weight(1f))
                        if (isWeb(link))
                            GlassIconAction(
                                ImageVector.vectorResource(R.drawable.ic_external_link),
                                ui(R.string.ui_original_source),
                            ) {
                                runCatching { uri.openUri(link) }
                            }
                        Box {
                            GlassIconAction(
                                Icons.Default.MoreVert,
                                ui(R.string.editorial_article_actions),
                            ) {
                                actions = true
                            }
                            DropdownMenu(actions, { actions = false }) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            ui(
                                                if (flag?.saved == true)
                                                    R.string.remove_from_favorites
                                                else R.string.add_to_favorites
                                            )
                                        )
                                    },
                                    onClick = {
                                        actions = false
                                        onSave()
                                    },
                                )
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
                        Modifier.onSizeChanged { titleHeight = it.height.toFloat() }.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            articleSource(article),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(0xffdedfe0),
                        )
                        Text(
                            article.optString("title"),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                        )
                    }
                }
            }
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Text(
                    articleMetadata(article),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.muted,
                )
                if (article.optBoolean("body_is_plain"))
                    Text(body, style = MaterialTheme.typography.bodyLarge)
                else Markdown(body)
                contextContent()
                val media = article.optJSONObject("media")
                if (media != null && isWeb(media.optString("source")) && cover != null) {
                    TextButton(
                        onClick = { runCatching { uri.openUri(media.getString("source")) } }
                    ) {
                        val kind =
                            if (media.optString("kind") == "thematic") R.string.mr_themed
                            else R.string.mr_art
                        Text(
                            listOf(
                                    ui(kind),
                                    articleField(media, "artist"),
                                    articleField(media, "license"),
                                )
                                .filter(String::isNotBlank)
                                .joinToString(" · "),
                            color = theme.link,
                        )
                    }
                }
            }
        }
    }
}
