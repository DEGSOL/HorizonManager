package dev.horizonmanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NewsScreen(panel: Panel, model: ManagerModel, issue: JSONObject?, filters: NewsFilters,
                        archived: Boolean, ai: () -> Unit, sources: () -> Unit, header: @Composable () -> Unit) {
    val run = issue?.optString("id").orEmpty()
    val loaded = run.isNotEmpty() && panel.selectedRun == run && panel.markdown.isNotEmpty()
    val activeJob = panel.jobs.firstOrNull { it.optString("state") in setOf("running", "queued") }
    val preparing = !archived && digestIsPreparing(panel.jobs)
    val state = rememberLazyListState()
    var full by rememberSaveable(run) { mutableStateOf(false) }
    var openId by rememberSaveable(run, panel.language) { mutableStateOf("") }
    var more by remember { mutableStateOf(false) }
    val flags = remember(panel.flags) { panel.flags.associateBy(ArticleFlag::article) }
    val query = filters.query.trim()
    val hasScores = remember(panel.articles) { panel.articles.any { it.optDouble("score").isFinite() } }
    val order = if (!hasScores && filters.order in listOf(NewsOrder.Score, NewsOrder.ScoreLow)) NewsOrder.Newest else filters.order
    val mode = filters.scope
    val category = filters.category
    val profile = filters.profile
    val incomplete = loaded && panel.articles.size < panel.total
    val primeOrder = (filters.explicitOrder || filters.order != NewsOrder.Score) && incomplete && !full
    val language = panel.language
    DisposableEffect(run, language, primeOrder) {
        if (primeOrder) model.loadChronology(run, language)
        onDispose { if (primeOrder) model.cancelChronology(run, language) }
    }
    val blocks = if (loaded && full) remember(panel.markdown) { markdownBlocks(panel.markdown) } else emptyList()
    val shown = remember(panel.articles, flags, query, order, mode, category, profile) {
        orderArticles(panel.articles.filter { article ->
            val flag = flags[article.optString("id")]
            (mode != NewsScope.Saved || flag?.saved == true) &&
                (mode != NewsScope.Unread || flag?.read != true) &&
                (category.isBlank() || articleField(article, "category") == category) &&
                (profile.isBlank() || articleField(article, "profile") == profile) &&
                (query.isEmpty() || listOf("title", "summary", "source", "category").any { articleField(article, it).contains(query, true) })
        }, order)
    }
    val palette = LocalHorizonPalette.current
    LazyColumn(Modifier.fillMaxSize().editorialInsets(), state, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item("header") { header() }
        if (loaded && !full) item("filters") { Column { NewsFilterOptions(filters, panel.articles) } }
        item("digest") {
            Tile(accented = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (issue != null) localTime(digestTime(issue)) else ui(R.string.ui_digest),
                            style = MaterialTheme.typography.titleLarge)
                        if (loaded) Text(ui(R.string.editorial_article_count, panel.total),
                            style = MaterialTheme.typography.labelLarge, color = palette.muted)
                    }
                    Box {
                        GlassIconAction(Icons.Default.MoreVert, ui(R.string.editorial_digest_actions)) { more = true }
                        DropdownMenu(more, { more = false }) {
                            if (!archived) {
                                DropdownMenuItem(text = { Text(ui(R.string.ui_start_collection)) },
                                    enabled = !panel.busy && activeJob == null, onClick = { more = false; model.submit() })
                                if (run.isNotEmpty()) DropdownMenuItem(text = { Text(ui(R.string.archive_early)) },
                                    onClick = { more = false; model.archiveDigest(run) })
                                if (panel.archive.isEmpty()) {
                                    DropdownMenuItem(text = { Text(ui(R.string.ui_configure_ai)) }, onClick = { more = false; ai() })
                                    DropdownMenuItem(text = { Text(ui(R.string.ui_add_a_source)) }, onClick = { more = false; sources() })
                                }
                            }
                            if (loaded && issue?.optJSONArray("languages")?.strings()?.contains(digestLocale()) == false) {
                                DropdownMenuItem(text = { Text(ui(R.string.ui_translate_issue)) },
                                    enabled = !panel.busy && activeJob == null, onClick = { more = false; model.translateDigest(run) })
                            }
                        }
                    }
                }
                val status = when {
                    preparing -> activeJob?.optString("phase")?.takeIf { it in setOf("fetch","score","filter","enrich","generate","save","delivery") }
                        ?.let(::phaseName) ?: ui(R.string.digest_preparing)
                    panel.syncing || panel.busy && !loaded -> ui(R.string.editorial_syncing)
                    loaded && (panel.connectionProblem.isNotBlank() || panel.lastContact.isEmpty()) -> ui(R.string.editorial_cached)
                    loaded -> ui(R.string.editorial_current)
                    panel.connectionProblem.isNotBlank() || panel.problem.isNotBlank() -> ui(R.string.issue_not_loaded)
                    issue != null -> ui(R.string.issue_not_loaded)
                    panel.archive.isEmpty() -> ui(R.string.ui_news_will_appear_here_after_horizon)
                    else -> ui(R.string.previous_issues_in_archive)
                }
                Text(status, style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                Reveal(preparing || panel.syncing || panel.busy && !loaded) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (loaded && issue?.optJSONArray("languages")?.strings()?.contains(digestLocale()) == false) {
                    Text(ui(R.string.ui_this_issue_is_shown_in_an), style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loaded) Button(onClick = { full = !full }) {
                        Text(ui(if (full) R.string.ui_back_to_articles else R.string.editorial_open_digest))
                    }
                    OutlinedButton(onClick = { model.refresh() }, enabled = !panel.busy && !panel.syncing) { Text(ui(R.string.ui_sync)) }
                    if (issue == null && !archived && !preparing) TextButton(onClick = { model.submit() },
                        enabled = !panel.busy && activeJob == null) { Text(ui(R.string.ui_start_collection)) }
                }
            }
        }
        if (loaded && !full) {
            if (primeOrder) item("sort-progress") {
                Tile {
                    Text(ui(R.string.chronology_progress, panel.articles.size, panel.total), style = MaterialTheme.typography.bodySmall)
                    if (panel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else if (panel.problem.isNotBlank()) TextButton(onClick = { model.loadChronology(run, panel.language) }) {
                        Text(ui(R.string.ui_retry))
                    }
                }
            }
            if (shown.isEmpty()) item("empty") {
                Tile {
                    Text(ui(if (panel.total == 0) R.string.ui_no_items_passed_the_horizon_filters
                        else R.string.ui_no_downloaded_articles_match_your_filters))
                }
            }
            items(shown, key = { "article:" + it.getString("id") }) { article ->
                val id = article.getString("id")
                val flag = flags[id]
                NewsCard(article, flag, Modifier.animateItem(), {
                    openId = id
                    if (flag?.read != true) model.flag(id, read = true)
                }) { model.flag(id, save = flag?.saved != true) }
            }
            if (incomplete && !primeOrder) item("more") {
                OutlinedButton(onClick = { model.openDigest(run, panel.language, panel.articles.size) }, enabled = !panel.busy) {
                    Text(ui(R.string.ui_load_more_of, panel.articles.size, panel.total))
                }
            }
        } else if (loaded) {
            items(blocks.size, key = { "markdown:$it" }) { index ->
                Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp)) {
                    MarkdownBlock(blocks[index])
                }
            }
        } else if (panel.busy) {
            items(3, key = { "loading:$it" }) {
                Tile {
                    HorizontalDivider(Modifier.fillMaxWidth(0.76f).padding(vertical = 6.dp), thickness = 7.dp, color = palette.elevated)
                    HorizontalDivider(Modifier.fillMaxWidth(0.52f).padding(vertical = 6.dp), thickness = 7.dp, color = palette.elevated)
                }
            }
        }
    }
    panel.articles.firstOrNull { loaded && it.optString("id") == openId }?.let { article ->
        val flag = flags[openId]
        ArticleDialog(article, flag, onSave = { model.flag(openId, save = flag?.saved != true) },
            onRead = { model.flag(openId, read = flag?.read != true) }) { openId = "" }
    }
}

@Composable
private fun NewsCard(article: JSONObject, flag: ArticleFlag?, modifier: Modifier, open: () -> Unit, save: () -> Unit) {
    val theme = LocalHorizonPalette.current
    val haptic = LocalHapticFeedback.current
    val saved = flag?.saved == true
    Tile(modifier.clickable(role = Role.Button, onClick = open)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(articleSource(article), Modifier.weight(1f), color = theme.muted,
                style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            GlassIconAction(if (saved) Icons.Default.Star else Icons.Outlined.Star,
                ui(if (saved) R.string.remove_from_favorites else R.string.add_to_favorites), active = saved) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                save()
            }
        }
        Text(article.optString("title"), color = if (flag?.read == true) theme.muted else theme.text,
            style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        val summary = article.optString("summary")
        if (summary.isNotBlank()) Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = theme.muted, style = MaterialTheme.typography.bodyMedium)
        Text(articleMetadata(article), style = MaterialTheme.typography.bodySmall, color = theme.tertiaryText)
    }
}

internal fun articleSource(article: JSONObject): String =
    listOf(articleField(article, "source").takeIf(String::isNotBlank)?.let(::label).orEmpty(),
        telegramPublisher(article), articleField(article, "category"), articleField(article, "profile"))
        .filter(String::isNotBlank).distinct().joinToString(" · ")

private fun telegramPublisher(article: JSONObject): String {
    if (articleField(article, "source") != "telegram") return ""
    val uri = runCatching { java.net.URI(articleField(article, "url")) }.getOrNull() ?: return ""
    if (uri.host != "t.me") return ""
    val channel = uri.path.trim('/').removePrefix("s/").substringBefore('/')
    return normalizeTelegramChannel(channel)?.let { "@$it" }.orEmpty()
}

internal fun articleMetadata(article: JSONObject): String =
    listOf(localTime(articleField(article, "time")), article.optDouble("score").takeIf(Double::isFinite)
        ?.let { "%.1f / 10".format(Locale.getDefault(), it) }.orEmpty()).filter(String::isNotBlank).joinToString(" · ")

@Composable
internal fun ArchiveScreen(issues: List<JSONObject>, panel: Panel, model: ManagerModel, open: (JSONObject) -> Unit,
                           header: @Composable () -> Unit) {
    var date by rememberSaveable { mutableStateOf("") }
    val byMonth = remember(issues, date) {
        issues.filter { date.isBlank() || digestTime(it).contains(date.trim()) || localTime(digestTime(it)).contains(date.trim(), true) }
            .groupBy { issue ->
                runCatching {
                    Instant.parse(digestTime(issue)).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                }.getOrDefault("")
            }
    }
    val theme = LocalHorizonPalette.current
    LazyColumn(Modifier.fillMaxSize().editorialInsets(), state = rememberLazyListState(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item("header") { header() }
        item("summary") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ui(if (panel.manuallyArchived.isEmpty()) R.string.archive_period else R.string.archive_period_with_manual,
                    panel.collectionHours), color = theme.muted)
                if (panel.connectionProblem.isNotBlank()) {
                    Text(ui(if (issues.isEmpty()) R.string.editorial_not_available else R.string.editorial_cached),
                        style = MaterialTheme.typography.bodySmall, color = theme.warning)
                    OutlinedButton(onClick = { model.refresh() }, enabled = !panel.busy) { Text(ui(R.string.ui_retry)) }
                }
                if (panel.syncing || panel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (issues.size > 7 || date.isNotEmpty()) ValueField(ui(R.string.editorial_archive_search), date, { date = it })
                if (byMonth.isEmpty() && !panel.busy) Text(ui(if (date.isBlank()) R.string.empty_archive else R.string.editorial_no_results))
            }
        }
        for ((month, entries) in byMonth) {
            item("month:$month") { Text(month, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 5.dp)) }
            items(entries, key = { it.getString("id") }) { issue ->
                val id = issue.getString("id")
                val count = issue.optInt("total", if (panel.selectedRun == id) panel.total else -1)
                val details = listOfNotNull(count.takeIf { it >= 0 }?.let { ui(R.string.editorial_article_count, it) },
                    if (id in panel.manuallyArchived) ui(R.string.editorial_manually_archived) else null).joinToString(" · ")
                GlassSettingRow(localTime(digestTime(issue)), details.ifBlank { ui(R.string.editorial_open_digest) }) { open(issue) }
            }
        }
        if (panel.hasMoreDigests) item("more") {
            OutlinedButton(onClick = model::loadMoreDigests, enabled = !panel.busy) { Text(ui(R.string.older_issues)) }
        }
    }
}

@Composable
internal fun Modifier.editorialInsets(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .navigationBarsPadding()
