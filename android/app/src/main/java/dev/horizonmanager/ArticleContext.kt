package dev.horizonmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal fun contextHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

private val activeContext = setOf("queued", "running")

@Composable
internal fun ArticleContext(
    article: JSONObject,
    request: suspend (Boolean) -> JSONObject,
) {
    val articleId = article.optString("id")
    val uri = LocalUriHandler.current
    val theme = LocalHorizonPalette.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestRequest by rememberUpdatedState(request)
    val saved = remember(article) {
        val blocks = article.optJSONArray("blocks")?.objects()?.filterNot { it.optBoolean("primary") }.orEmpty()
        val sources = article.optJSONArray("citations")?.objects().orEmpty().filter { isWeb(it.optString("url")) }
        if (blocks.isEmpty() && sources.isEmpty()) null
        else JSONObject().put("state", "ready").put("blocks", JSONArray(blocks))
            .put("sources", JSONArray(sources))
    }
    var result by remember(articleId) { mutableStateOf(saved ?: JSONObject()) }
    var loading by remember(articleId) { mutableStateOf(false) }
    var checking by remember(articleId) { mutableStateOf(true) }
    var show by remember(articleId) { mutableStateOf(false) }
    val state = result.optString("state")
    val active = loading || state in activeContext

    suspend fun load(submit: Boolean): JSONObject =
        try {
            latestRequest(submit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val code = (error as? Problem)?.code.orEmpty()
            if (saved != null) saved
            else if (submit || code == "AGENT_UPDATE_REQUIRED" || result.optString("state") in activeContext)
                JSONObject().put("state", "failed").put("code", code)
            else JSONObject()
        }

    LaunchedEffect(articleId) {
        try {
            result = load(false)
            if (result.optString("state") in activeContext) show = true
        } finally {
            checking = false
        }
    }
    LaunchedEffect(articleId, lifecycle, state in activeContext) {
        if (state in activeContext) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (result.optString("state") in activeContext) {
                    delay(2500)
                    result = load(false)
                }
            }
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically) {
        if (active) {
            DottedProgress(Modifier.width(44.dp), dots = 5)
            Text(ui(R.string.article_context_pending), Modifier.weight(1f, fill = false)
                .padding(horizontal = 8.dp).semantics {
                    liveRegion = LiveRegionMode.Polite
                }, color = theme.muted, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(enabled = !active && !checking, onClick = {
            if (state == "ready") {
                show = !show
            } else {
                show = true
                loading = true
                scope.launch {
                    try {
                        result = load(true)
                    } finally {
                        loading = false
                    }
                }
            }
        }) {
            Icon(ImageVector.vectorResource(R.drawable.ic_ai_sparkles), null, Modifier.size(19.dp))
            Spacer(Modifier.width(7.dp))
            Text(ui(R.string.article_more))
        }
    }
    if (state == "failed" && !loading) {
        val message = when (result.optString("code")) {
            "AGENT_UPDATE_REQUIRED" -> R.string.article_context_update
            "TIMEOUT" -> R.string.article_context_timeout
            "AI_SETUP_REQUIRED", "AUTH", "HTTP_401", "HTTP_403" -> R.string.article_context_ai_setup
            "RATE_LIMIT", "HTTP_429" -> R.string.article_context_rate_limit
            "ARTICLE_UNKNOWN", "RUN_UNKNOWN", "ARTICLE_PROFILE_UNAVAILABLE" -> R.string.article_context_unavailable
            else -> R.string.article_context_failed
        }
        Text(ui(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = theme.danger, style = MaterialTheme.typography.bodySmall)
    }
    Reveal(show && state == "ready") {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(ui(R.string.article_context_heading), style = MaterialTheme.typography.titleMedium)
            result.optJSONArray("blocks")?.objects().orEmpty().forEach { block ->
                val title = block.optString("title")
                if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleSmall)
                Markdown(block.optString("content"))
            }
            result.optJSONArray("sources")?.objects().orEmpty().forEach { source ->
                val url = source.optString("url")
                if (isWeb(url)) TextButton(onClick = { runCatching { uri.openUri(url) } }) {
                    Text(source.optString("title").ifBlank { url }, color = theme.link)
                }
            }
        }
    }
}
