package dev.horizonmanager

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.Instant

internal enum class NewsOrder(@param:StringRes val label: Int) {
    Issue(R.string.mr_issue_order), Score(R.string.editorial_score_high), ScoreLow(R.string.editorial_score_low),
    Newest(R.string.editorial_newest), Oldest(R.string.oldest_first), Title(R.string.ui_by_title),
}

internal enum class NewsScope { All, Unread, Saved }

@Stable
internal class NewsFilters {
    var query by mutableStateOf("")
    var scope by mutableStateOf(NewsScope.All)
    var profile by mutableStateOf("")
    var category by mutableStateOf("")
    var order by mutableStateOf(NewsOrder.Score)
    var explicitOrder by mutableStateOf(false)
    var searchOpen by mutableStateOf(false)
    var optionsOpen by mutableStateOf(false)

    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<NewsFilters, Any>(
            save = { listOf(it.query, it.scope.name, it.profile, it.category, it.order.name, it.explicitOrder, it.searchOpen, it.optionsOpen) },
            restore = { saved -> NewsFilters().apply {
                query = saved[0] as String
                scope = NewsScope.valueOf(saved[1] as String)
                profile = saved[2] as String
                category = saved[3] as String
                order = NewsOrder.valueOf(saved[4] as String)
                explicitOrder = saved[5] as Boolean
                searchOpen = saved[6] as Boolean
                optionsOpen = saved[7] as Boolean
            } }
        )
    }
}

internal fun articleField(article: JSONObject, name: String): String =
    article.optString(name).takeUnless { it == "null" }.orEmpty()

internal fun chronologicalTime(article: JSONObject): Instant =
    runCatching { Instant.parse(article.optString("time")) }.getOrDefault(Instant.MAX)

internal fun orderArticles(articles: List<JSONObject>, order: NewsOrder): List<JSONObject> = when (order) {
    NewsOrder.Issue -> articles
    NewsOrder.Oldest -> articles.sortedBy(::chronologicalTime)
    NewsOrder.Newest -> articles.sortedByDescending { chronologicalTime(it).takeUnless { date -> date == Instant.MAX } ?: Instant.MIN }
    NewsOrder.Score -> articles.sortedByDescending { it.optDouble("score").takeIf(Double::isFinite) ?: Double.NEGATIVE_INFINITY }
    NewsOrder.ScoreLow -> articles.sortedBy { it.optDouble("score").takeIf(Double::isFinite) ?: Double.POSITIVE_INFINITY }
    NewsOrder.Title -> articles.sortedBy { it.optString("title").lowercase() }
}

@Composable
internal fun GlassIconAction(icon: ImageVector, description: String, enabled: Boolean = true,
                            active: Boolean? = null, onClick: () -> Unit) {
    val palette = LocalHorizonPalette.current
    val indicator by animateColorAsState(if (active == true) palette.tint.copy(alpha = 0.27f) else Color.Transparent,
        tween(180), label = "icon highlight")
    val tint by animateColorAsState(when {
        !enabled -> palette.muted.copy(alpha = 0.53f)
        active == true -> palette.link
        else -> palette.text
    }, tween(180), label = "icon tint")
    val glyph: @Composable () -> Unit = {
        Icon(icon, description,
            Modifier.size(36.dp).background(indicator, CircleShape).padding(8.dp),
            tint = tint)
    }
    if (active != null) IconToggleButton(active, { onClick() }, Modifier.size(48.dp), enabled, content = glyph)
    else IconButton(onClick, Modifier.size(48.dp), enabled, content = glyph)
}

@Composable
internal fun NewsToolbar(filters: NewsFilters, enabled: Boolean) {
    Row(horizontalArrangement=Arrangement.spacedBy(4.dp),verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.frost(CircleShape)) { GlassIconAction(Icons.Default.Search, ui(R.string.news_search), enabled, active = filters.searchOpen || filters.query.isNotBlank()) {
            filters.searchOpen = !filters.searchOpen
            if (filters.searchOpen) filters.optionsOpen = false
        } }
        Box(Modifier.frost(CircleShape)) { GlassIconAction(ImageVector.vectorResource(R.drawable.ic_sliders), ui(R.string.editorial_filters_sort), enabled,
            active = filters.optionsOpen || filters.scope != NewsScope.All || filters.profile.isNotEmpty() ||
                filters.category.isNotEmpty() || filters.explicitOrder) {
            filters.optionsOpen = !filters.optionsOpen
            if (filters.optionsOpen) filters.searchOpen = false
        } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PinnedNewsControls(filters: NewsFilters, articles: List<JSONObject>, maxHeight: Dp) {
    val scopes = listOf(NewsScope.All to R.string.ui_all, NewsScope.Unread to R.string.ui_unread, NewsScope.Saved to R.string.ui_saved)
    Reveal(filters.searchOpen) {
        OutlinedTextField(value = filters.query, onValueChange = { filters.query = it },
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            label = { Text(ui(R.string.ui_search_downloaded_articles)) }, singleLine = true, colors = horizonFieldColors(),
            trailingIcon = if (filters.query.isEmpty()) null else ({
                GlassIconAction(Icons.Default.Close, ui(R.string.clear_search)) { filters.query = "" }
            }))
    }
    Reveal(filters.optionsOpen) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .glassPanel(MaterialTheme.shapes.large, raised = true)
            .heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                scopes.forEach { (mode, title) ->
                    FilterChip(selected = filters.scope == mode, onClick = { filters.scope = mode }, label = { Text(ui(title)) })
                }
            }
            val hasScore = remember(articles) { articles.any { it.optDouble("score").isFinite() } }
            val orders = NewsOrder.entries.filter { hasScore || it !in listOf(NewsOrder.Score, NewsOrder.ScoreLow) }
            val effective = filters.order.takeIf { it in orders } ?: NewsOrder.Newest
            Choice(ui(R.string.ui_sort), orders.map { it.name }, effective.name, {
                filters.order = NewsOrder.valueOf(it)
                filters.explicitOrder = true
            }, { ui(NewsOrder.valueOf(it).label) }, row = true)
            for ((field, current, change) in listOf(
                Triple("category", filters.category, { it: String -> filters.category = it }),
                Triple("profile", filters.profile, { it: String -> filters.profile = it }),
            )) {
                val values = remember(articles, field) {
                    articles.map { articleField(it, field) }.filter(String::isNotBlank).distinct().sorted()
                }
                if (values.isNotEmpty() || current.isNotEmpty()) Choice(label(field), (listOf("") + values + current).distinct(),
                    current, change, { it.ifBlank { ui(R.string.ui_all) } }, row = true)
            }
        }
    }
}
