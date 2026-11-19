package dev.horizonmanager

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.unifiedpush.android.connector.UnifiedPush
import java.util.Locale

@Composable
internal fun Archive(issues: List<JSONObject>, hours: Int, hasMore: Boolean, busy: Boolean,
                     hasEarlyArchives: Boolean, open: (JSONObject) -> Unit, loadMore: () -> Unit) {
    Text(ui(if (hasEarlyArchives) R.string.archive_period_with_manual else R.string.archive_period, hours),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (issues.isEmpty()) {
        Tile { Text(ui(R.string.empty_archive), style = MaterialTheme.typography.titleMedium) }
    }
    issues.forEach { issue ->
        key(issue.getString("id")) {
            GlassSettingRow(localTime(digestTime(issue)), ui(R.string.ui_full_digest), enabled = !busy) { open(issue) }
        }
    }
    if (hasMore) {
        OutlinedButton(enabled = !busy, onClick = loadMore) { Text(ui(R.string.older_issues)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun News(panel: Panel, model: ManagerModel, issue: JSONObject?, ai: () -> Unit, sources: () -> Unit,
                  feedFilters: NewsFilters? = null) {
    val filters = feedFilters ?: remember { NewsFilters() }
    val isFeed = feedFilters != null
    val preparing = isFeed && digestIsPreparing(panel.jobs)
    val palette = LocalHorizonPalette.current
    var open by remember(panel.selectedRun, panel.language) { mutableStateOf<JSONObject?>(null) }
    var markdown by remember(panel.selectedRun) { mutableStateOf(false) }
    if (preparing) {
        Tile {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp), color = palette.link, strokeWidth = 2.5.dp)
                Column {
                    Text(ui(R.string.digest_preparing), style = MaterialTheme.typography.titleMedium)
                    Text(ui(R.string.digest_preparing_detail), style = MaterialTheme.typography.bodySmall, color = palette.muted)
                }
            }
        }
    }
    if (issue == null) {
        if (preparing) return
        Tile {
            Text(ui(if (panel.archive.isEmpty()) R.string.ui_your_first_digest else R.string.empty_current_digest),
                style = MaterialTheme.typography.titleLarge)
            Text(when {
                panel.archive.isEmpty() -> ui(R.string.ui_news_will_appear_here_after_horizon)
                panel.manuallyArchived.isNotEmpty() -> ui(R.string.previous_issues_in_archive)
                else -> ui(R.string.current_period, panel.collectionHours)
            })
            if (panel.archive.isEmpty()) Text(ui(R.string.ui_choose_an_ai_model_in_settings))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.submit() }, enabled = !panel.busy) { Text(ui(R.string.ui_start_collection)) }
                if (panel.archive.isEmpty()) {
                    OutlinedButton(onClick = ai, enabled = panel.schema.length() > 0) { Text(ui(R.string.ui_configure_ai)) }
                    OutlinedButton(onClick = sources) { Text(ui(R.string.ui_add_a_source)) }
                }
                TextButton(onClick = { model.refresh() }, enabled = !panel.busy) { Text(ui(R.string.ui_check_the_feed)) }
            }
        }
        return
    }
    val run = issue.getString("id")
    val languages = issue.getJSONArray("languages").strings()
    val loaded = panel.selectedRun == run && panel.markdown.isNotEmpty()
    val needsTranslation = languages.isNotEmpty() && digestLocale() !in languages
    val flags = panel.flags.associateBy { it.article }
    Tile(accented = isFeed) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(ui(R.string.ui_digest), style = MaterialTheme.typography.titleMedium)
                Text(localTime(digestTime(issue)), style = MaterialTheme.typography.bodyMedium, color = palette.muted)
            }
            if (isFeed) {
                GlassIconAction(Icons.Default.Refresh, ui(R.string.ui_sync), enabled = !panel.busy) {
                    model.refresh()
                }
                val issueIcon = if (markdown) Icons.AutoMirrored.Filled.List else ImageVector.vectorResource(R.drawable.ic_issue)
                GlassIconAction(issueIcon, ui(if (markdown) R.string.ui_back_to_articles else R.string.ui_full_digest),
                    enabled = loaded, active = markdown) { markdown = !markdown }
                GlassIconAction(ImageVector.vectorResource(R.drawable.ic_archive_down), ui(R.string.archive_early)) {
                    model.archiveDigest(run)
                }
            }
        }
        if (!loaded) {
            if (panel.busy || panel.problem.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(ui(R.string.issue_not_loaded))
        }
        if (!isFeed || (loaded && needsTranslation)) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loaded && needsTranslation) {
                TextButton(onClick = { model.translateDigest(panel.selectedRun) },
                    enabled = !panel.busy && panel.jobs.none { it.optString("state") in setOf("queued", "running") }) {
                    Text(ui(R.string.ui_translate_issue))
                }
            }
            if (!isFeed) {
                TextButton(onClick = { model.refresh() }, enabled = !panel.busy) { Text(ui(R.string.ui_sync)) }
                if (loaded) TextButton(onClick = { markdown = !markdown }) {
                    Text(ui(if (markdown) R.string.ui_back_to_articles else R.string.ui_full_digest))
                }
            }
        }
    }
    if (!loaded) return
    if (needsTranslation) {
        Text(ui(R.string.ui_this_issue_is_shown_in_an), style = MaterialTheme.typography.bodySmall)
    }
    if (markdown) {
        if (panel.markdown.isNotEmpty()) Tile { Markdown(panel.markdown) }
        else Tile { Text(ui(R.string.ui_the_markdown_digest_has_not_been)) }
        return
    }
    if (!isFeed) {
        ValueField(ui(R.string.ui_search_downloaded_articles), filters.query, { filters.query = it })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filters.saved, { filters.saved = !filters.saved }, { Text(ui(R.string.ui_saved)) })
            FilterChip(filters.unread, { filters.unread = !filters.unread }, { Text(ui(R.string.ui_unread)) })
            Choice(ui(R.string.ui_profile), listOf("") + articleProfiles(panel.articles),
                filters.profile, { filters.profile = it }, { it.ifBlank { ui(R.string.ui_all) } })
            Choice(ui(R.string.ui_sort), NewsOrder.entries.map { it.name }, filters.order.name,
                { filters.order = NewsOrder.valueOf(it) }, { ui(NewsOrder.valueOf(it).label) })
        }
    }
    val needsChronologyPages = filters.order == NewsOrder.Oldest && panel.articles.size < panel.total
    LaunchedEffect(run, panel.language, filters.order) {
        if (needsChronologyPages) model.loadChronology(run, panel.language)
    }
    if (needsChronologyPages) {
        Tile {
            if (panel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(ui(R.string.chronology_progress, panel.articles.size, panel.total))
            if (!panel.busy && panel.problem.isNotEmpty()) {
                TextButton(onClick = { model.loadChronology(run, panel.language) }) { Text(ui(R.string.ui_retry)) }
            }
        }
        return
    }
    val shown = panel.articles.asSequence().filter { row ->
        val flag = flags[row.getString("id")]
        (!filters.saved || flag?.saved == true) && (!filters.unread || flag?.read != true) &&
            (filters.profile.isEmpty() || row.optString("profile") == filters.profile) &&
            (filters.query.isBlank() || listOf("title", "summary", "source", "category").any { row.optString(it).contains(filters.query.trim(), true) })
    }.let { filtered ->
        when (filters.order) {
            NewsOrder.Oldest -> filtered.sortedBy(::chronologicalTime)
            NewsOrder.Title -> filtered.sortedBy { it.optString("title").lowercase() }
            NewsOrder.Score -> filtered.sortedByDescending { it.optDouble("score", -1.0) }
        }
    }.toList()
    if (shown.isEmpty()) Tile {
        Text(if (panel.total == 0) ui(R.string.ui_no_items_passed_the_horizon_filters) else ui(R.string.ui_no_downloaded_articles_match_your_filters))
    }
    for (article in shown) key(article.getString("id")) {
        val id = article.getString("id")
        val flag = flags[id]
        val source = article.optString("source")
        val category = article.optString("category").takeUnless { it == "null" }.orEmpty()
        val sourceLabel = listOf(source, category, article.optString("profile").takeUnless { it == "null" }.orEmpty())
            .filter { it.isNotBlank() }.joinToString(" · ")
        Tile(Modifier.clickable { open = article; model.flag(id, read = true) }) {
            if (isFeed) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(sourceLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                        color = palette.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val saved = flag?.saved == true
                    val read = flag?.read == true
                    GlassIconAction(if (saved) Icons.Default.Star else Icons.Outlined.Star,
                        ui(if (saved) R.string.remove_from_favorites else R.string.add_to_favorites), active = saved) {
                        model.flag(id, save = !saved)
                    }
                    GlassIconAction(if (read) Icons.Default.Check else Icons.Outlined.MailOutline,
                        ui(if (read) R.string.mark_unread else R.string.ui_mark_as_read), active = read) {
                        model.flag(id, read = !read)
                    }
                }
            } else {
                Text(sourceLabel, style = MaterialTheme.typography.labelLarge, color = palette.muted)
            }
            Text(article.optString("title"), style = MaterialTheme.typography.titleLarge)
            val summary = article.optString("summary")
            if (summary.isNotBlank()) Text(summary, maxLines = if (isFeed) 3 else 5, overflow = TextOverflow.Ellipsis,
                color = if (isFeed) palette.muted else palette.text)
            Text(localTime(article.optString("time")) +
                article.optDouble("score").takeIf { it.isFinite() }?.let { " · %.1f / 10".format(Locale.ROOT, it) }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (isFeed) lerp(palette.muted, Color(0xFF969696), 0.55f) else palette.text)
            if (!isFeed) FlowRow {
                TextButton(onClick = { model.flag(id, save = flag?.saved != true) }) { Text(if (flag?.saved == true) ui(R.string.ui_saved_2e21e4) else ui(R.string.ui_save)) }
                TextButton(onClick = { model.flag(id, read = flag?.read != true) }) { Text(if (flag?.read == true) ui(R.string.ui_read) else ui(R.string.ui_mark_as_read)) }
            }
        }
    }
    if (panel.articles.size < panel.total) {
        OutlinedButton(onClick = { model.openDigest(panel.selectedRun, panel.language, panel.articles.size) }) {
            Text(ui(R.string.ui_load_more_of, panel.articles.size, panel.total))
        }
    }
    open?.let { article -> ArticleDialog(article) { open = null } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Sources(panel: Panel, model: ManagerModel) {
    val config = panel.config.optJSONObject("config")
    val sourceRoot = panel.schema.optJSONObject("config")
    if (config == null || sourceRoot == null) {
        Tile { Text(ui(R.string.ui_connect_to_the_agent_to_manage)); TextButton(onClick = { model.refresh() }) { Text(ui(R.string.ui_connect)) } }
        return
    }
    val profiles = panel.config.getJSONArray("profiles")
    val ids = profileIds(profiles)
    val original = config.getJSONObject("sources")
    var working by remember(panel.config.optInt("revision")) { mutableStateOf(original.copyObject()) }
    var type by remember { mutableStateOf("rss") }
    val types = panel.schema.optJSONArray("sources")?.strings().orEmpty()
    val sourceSchema = definition(sourceRoot, sourceRoot.getJSONObject("properties").getJSONObject("sources")).getJSONObject("properties")
    Tile {
        Text(ui(R.string.ui_horizon_sources), style = MaterialTheme.typography.titleLarge)
        Choice(ui(R.string.ui_integration), types, type, { type = it })
        when (type) {
            "rss" -> Text(ui(R.string.ui_add_an_rss_or_atom_url))
            "telegram" -> Text(ui(R.string.ui_enter_the_public_channel_name_without))
            "twitter" -> Text(ui(R.string.ui_apify_requires_apify_token_and_access))
            "openbb" -> if (!panel.status.optBoolean("openbb")) Text(ui(R.string.ui_openbb_requires_an_additional_package_on))
            "github" -> Text(ui(R.string.ui_use_user_events_for_user_activity))
            "google_news", "gdelt" -> Text(ui(R.string.ui_enter_a_search_query_and_optionally))
        }
        key(type) {
            EditValue(sourceRoot, sourceSchema.getJSONObject(type), type, working.opt(type), {
                working = working.copyObject().put(type, it)
            }, ids, expandedByDefault = true)
        }
        Row {
            Button(onClick = { model.saveConfig(config.copyObject().put("sources", working), profiles) },
                enabled = !panel.busy && working.toString() != original.toString()) { Text(ui(R.string.ui_apply_source_settings)) }
            TextButton(onClick = { working = original.copyObject() }) { Text(ui(R.string.ui_discard_changes)) }
        }
    }
    Tile {
        Text(ui(R.string.ui_test_saved_sources), style = MaterialTheme.typography.titleMedium)
        when (val value = original.opt(type)) {
            is JSONArray -> for (index in 0 until value.length()) {
                val entry = value.optJSONObject(index)
                val name = entry?.optString("name")?.ifBlank { null } ?: entry?.optString("username")?.ifBlank { null }
                    ?: entry?.optString("repo")?.ifBlank { null } ?: "#${index + 1}"
                TextButton(onClick = { model.submit("source", type, index = index) }, enabled = !panel.busy) { Text(ui(R.string.ui_test, name)) }
            }
            is JSONObject -> {
                TextButton(onClick = { model.submit("source", type) }, enabled = !panel.busy) { Text(ui(R.string.ui_test, label(type))) }
                val buckets = when (type) {
                    "telegram" -> listOf("channels")
                    "reddit" -> listOf("subreddits", "users")
                    "openbb" -> listOf("watchlists")
                    "twitter" -> listOf("users", "keywords")
                    else -> emptyList()
                }
                for (bucket in buckets) value.optJSONArray(bucket)?.let { items ->
                    for (index in 0 until items.length()) {
                        val item = items.get(index)
                        val name = (item as? JSONObject)?.let {
                            listOf("name", "channel", "subreddit", "username").firstNotNullOfOrNull { key -> it.optString(key).ifBlank { null } }
                        } ?: if (item is String) item else "#${index + 1}"
                        TextButton(onClick = { model.submit("source", type, bucket, index) }, enabled = !panel.busy) { Text(ui(R.string.ui_test, name)) }
                    }
                }
            }
            else -> Text(ui(R.string.ui_this_integration_has_not_been_configured))
        }
    }
    if (panel.sourceHistory.isNotEmpty()) Tile {
        Text(ui(R.string.ui_recent_checks), style = MaterialTheme.typography.titleMedium)
        panel.sourceHistory.take(30).forEach { history ->
            Text("${history.optString("source")} · ${stateName(history.optString("status"))}")
            Text(ui(R.string.ui_checked, localTime(history.optString("checked"))) +
                history.optString("last_success").takeIf { it.isNotBlank() && it != "null" }?.let { ui(R.string.ui_last_success, localTime(it)) }.orEmpty(),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun stateName(value: String) = mapOf("queued" to ui(R.string.ui_waiting), "running" to ui(R.string.ui_running_eff79c),
    "success" to ui(R.string.ui_succeeded), "empty" to ui(R.string.ui_no_posts), "partial_failure" to ui(R.string.ui_partial_failure),
    "failure" to ui(R.string.ui_error_72aecd), "failed" to ui(R.string.ui_error_72aecd), "cancelled" to ui(R.string.ui_stopped), "timeout" to ui(R.string.ui_timed_out), "interrupted" to ui(R.string.ui_interrupted))[value] ?: value

private fun phaseName(value: String) = mapOf("fetch" to ui(R.string.ui_collecting_news), "score" to ui(R.string.ui_ai_scoring),
    "filter" to ui(R.string.ui_filtering_and_deduplication), "enrich" to ui(R.string.ui_analysis_and_context),
    "generate" to ui(R.string.ui_generating_digest), "save" to ui(R.string.ui_saving), "delivery" to ui(R.string.ui_delivery))[value] ?: stateName(value)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Control(panel: Panel, model: ManagerModel, confirm: (String, () -> Unit) -> Unit,
                     backup: () -> Unit, restore: () -> Unit, configure: (String) -> Unit) {
    val status = panel.status
    val active = panel.jobs.firstOrNull { it.optString("state") in listOf("queued", "running") }
    val latest = panel.jobs.firstOrNull { it.optString("kind") == "run" && it.optString("state") == "success" }
    val issue = panel.events.firstOrNull { it.optString("level") in listOf("ERROR", "CRITICAL") }
    var restart by remember { mutableStateOf(false) }
    if (restart) PasswordDialog(ui(R.string.ui_restart_agent),
        ui(R.string.ui_the_current_job_may_be_interrupted),
        close = { restart = false }) { password -> restart = false; model.restart(password) }
    Tile {
        Text(ui(R.string.ui_horizon_on_the_vps), style = MaterialTheme.typography.titleLarge)
        if (status.length() == 0) Text(ui(R.string.ui_monitoring_data_has_not_loaded_yet))
        else {
            Text(ui(R.string.ui_agent_horizon, status.optString("agent"), status.optString("horizon").take(12)))
            val mem = status.optJSONObject("memory")
            val total = mem?.optLong("MemTotal") ?: 0
            val available = mem?.optLong("MemAvailable") ?: 0
            Text(ui(R.string.ui_ram_mib_free_disk_mib, (total - available) / 1_048_576, total / 1_048_576, status.optLong("disk_free") / 1_048_576))
            Text(ui(R.string.ui_cpu_1_minute_load_average, status.optInt("cpu"), status.optDouble("load")))
            Text(ui(R.string.ui_next_run, localEpoch(status.optDouble("next"))))
        }
        latest?.let { Text(ui(R.string.ui_last_success_517d5d, localTime(it.optString("updated")))) }
        issue?.let { Text(ui(R.string.ui_last_error, errorText(it.optString("code"))), color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { model.submit() }, enabled = active == null && !panel.busy) { Text(ui(R.string.ui_start_collection)) }
            OutlinedButton(onClick = { model.refresh() }) { Text(ui(R.string.ui_check_status)) }
        }
    }
    active?.let { job -> Tile {
        Text(phaseName(job.optString("phase")), style = MaterialTheme.typography.titleMedium)
        Text(stateName(job.optString("state")))
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(job.getString("id"), style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { confirm(ui(R.string.ui_stop_this_job_the_provider_may)) { model.stop(job.getString("id")) } }) { Text(ui(R.string.ui_stop)) }
    } }
    Tile {
        Text(ui(R.string.ui_maintenance), style = MaterialTheme.typography.titleMedium)
        FlowRow {
            TextButton(onClick = { model.check() }) { Text(ui(R.string.ui_check_configuration)) }
            TextButton(onClick = { configure("schedule") }) { Text(ui(R.string.ui_schedule)) }
            TextButton(onClick = { restart = true }) { Text(ui(R.string.ui_restart_agent)) }
            TextButton(onClick = { model.checkVersions() }) { Text(ui(R.string.ui_check_horizon_version)) }
            TextButton(onClick = backup) { Text(ui(R.string.ui_backup)) }
            TextButton(onClick = restore) { Text(ui(R.string.ui_restore_backup_4afed2)) }
        }
    }
    if (panel.jobs.isNotEmpty()) Tile {
        Text(ui(R.string.ui_job_history), style = MaterialTheme.typography.titleMedium)
        panel.jobs.take(25).forEach { job ->
            val kind = when (job.optString("kind")) { "ai" -> ui(R.string.ui_ai_test); "source" -> ui(R.string.ui_source_test); "localize" -> ui(R.string.ui_translation); else -> ui(R.string.ui_digest) }
            Text("$kind · ${stateName(job.optString("state"))} · ${localTime(job.optString("created"))}")
            Text(job.optString("id"), style = MaterialTheme.typography.labelSmall)
            job.optJSONObject("result")?.let { result ->
                val counts = listOf("raw" to ui(R.string.ui_collected), "scored" to ui(R.string.ui_scored), "kept" to ui(R.string.ui_in_issue), "enriched" to ui(R.string.ui_enriched))
                    .mapNotNull { (key, title) -> if (result.has(key)) "$title ${result.getInt(key)}" else null }
                if (counts.isNotEmpty()) Text(counts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            if (active == null && job.optString("kind") in setOf("ai", "run") && job.optString("state") in setOf("failed", "timeout", "interrupted")) {
                TextButton(onClick = { model.submit(job.optString("kind")) }) { Text(ui(R.string.ui_retry)) }
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Journal(panel: Panel, confirm: (String, () -> Unit) -> Unit, model: ManagerModel, export: (String) -> Unit) {
    var level by remember { mutableStateOf("") }
    var component by remember { mutableStateOf("") }
    var period by remember { mutableStateOf(ui(R.string.ui_all_dates)) }
    var search by remember { mutableStateOf("") }
    var job by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var page by remember { mutableIntStateOf(80) }
    val ctx = LocalContext.current
    val components = panel.events.map { it.optString("component") }.distinct()
    val cutoff = when (period) {
        ui(R.string.ui_last_24_hours) -> java.time.Instant.now().minusSeconds(86400)
        ui(R.string.ui_last_7_days) -> java.time.Instant.now().minusSeconds(604800)
        else -> java.time.Instant.EPOCH
    }
    val events = panel.events.filter { row ->
        (level.isBlank() || row.optString("level") == level) &&
            (component.isBlank() || row.optString("component") == component) &&
            (job.isBlank() || row.optString("job") == job) &&
            runCatching { java.time.Instant.parse(row.optString("time")) >= cutoff }.getOrDefault(false) &&
            (search.isBlank() || listOf("job", "code", "detail").any { row.optString(it).contains(search, true) })
    }
    ValueField(ui(R.string.ui_search_events_or_ids), search, { search = it })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Choice(ui(R.string.ui_level), listOf("", "DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"), level, { level = it }, { it.ifBlank { ui(R.string.ui_all) } })
        Choice(ui(R.string.ui_component), listOf("") + components, component, { component = it }, { it.ifBlank { ui(R.string.ui_all) } })
        Choice(ui(R.string.ui_time_range), listOf(ui(R.string.ui_all_dates), ui(R.string.ui_last_24_hours), ui(R.string.ui_last_7_days)), period, { period = it }, { it })
    }
    if (job.isNotEmpty()) TextButton(onClick = { job = "" }) { Text(ui(R.string.ui_clear_operation_filter, job)) }
    FlowRow {
        TextButton(onClick = { model.refresh() }) { Text(ui(R.string.ui_sync)) }
        TextButton(enabled = events.isNotEmpty(), onClick = { export(JSONArray(events).toString(2)) }) { Text(ui(R.string.ui_export)) }
        TextButton(enabled = panel.events.isNotEmpty(), onClick = {
            val max = panel.events.maxOfOrNull { it.optLong("id") } ?: 0
            confirm(ui(R.string.ui_delete_downloaded_journal_entries_and_vps)) { model.clearEvents(max) }
        }) { Text(ui(R.string.ui_clear)) }
    }
    if (events.isEmpty()) Tile { Text(ui(R.string.ui_no_entries_match_these_filters)) }
    events.take(page).forEach { row ->
        Tile(Modifier.clickable { detail = row }) {
            val warning = row.optString("level") in listOf("ERROR", "CRITICAL", "WARNING")
            Text("${row.optString("component")} · ${row.optString("level")} · ${localTime(row.optString("time"))}",
                style = MaterialTheme.typography.labelMedium,
                color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(row.optString("detail").ifBlank { phaseName(row.optString("code")) }, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
    if (events.size > page) TextButton(onClick = { page += 80 }) { Text(ui(R.string.ui_more_entries)) }
    detail?.let { row ->
        val code = row.optString("code")
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(code) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(localTime(row.optString("time")) + " · ${row.optString("component")}")
                    Text(errorText(code))
                    val technical = row.optString("detail")
                    if (technical.isNotBlank()) Text(technical)
                    val id = row.optString("job")
                    if (id.isNotBlank() && id != "null") TextButton(onClick = { job = id; detail = null }) { Text(ui(R.string.ui_events_for_this_operation)) }
                }
            }, confirmButton = {
                Button(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Horizon", row.toString(2)))
                }) { Text(ui(R.string.ui_copy)) }
            }, dismissButton = { TextButton(onClick = { detail = null }) { Text(ui(R.string.ui_close)) } })
    }
}

private fun profileIds(profiles: JSONArray) = profiles.objects().mapNotNull { it.optJSONObject("definition")?.optString("id") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Settings(panel: Panel, model: ManagerModel, open: (String) -> Unit, ssh: () -> Unit,
                      import: (String) -> Unit, export: (String, String) -> Unit, permission: () -> Unit,
                      confirm: (String, () -> Unit) -> Unit) {
    val server = requireNotNull(panel.server)
    val config = panel.config.optJSONObject("config")
    val context = LocalContext.current
    val registration by remember(context, server.id) { DigestNotifications.observeRegistration(context, server.id) }
        .collectAsState(initial = DigestNotifications.registration(context, server.id))
    var upgrade by remember { mutableStateOf(false) }
    var chooseDistributor by remember { mutableStateOf(false) }
    var pushMessage by remember { mutableStateOf("") }
    if (upgrade) PasswordDialog(ui(R.string.ui_update_vps_agent),
        ui(R.string.ui_settings_and_the_archive_will_stay),
        close = { upgrade = false }) { password -> upgrade = false; model.updateAgent(password) }
    if (chooseDistributor) {
        val distributors = UnifiedPush.getDistributors(context)
        AlertDialog(onDismissRequest = { chooseDistributor = false },
            title = { Text(ui(R.string.ui_push_notification_service)) },
            text = {
                Column {
                    if (distributors.isEmpty()) Text(ui(R.string.ui_install_ntfy_and_enable_unifiedpush_in))
                    distributors.forEach { distributor ->
                        val name = runCatching {
                            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(distributor, 0)).toString()
                        }.getOrDefault(distributor)
                        TextButton(onClick = {
                            chooseDistributor = false
                            permission()
                            DigestNotifications.register(context, distributor, server.id)
                            pushMessage = ui(R.string.ui_notification_registration_requested)
                        }) { Text(name) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { chooseDistributor = false }) { Text(ui(R.string.ui_close)) } })
    }
    val pages = if (config != null) listOf("ai" to ui(R.string.ui_model_api_address_and_request_limits),
        "profiles" to ui(R.string.ui_prompts_scoring_criteria_and_output_blocks), "digest" to ui(R.string.ui_quotas_order_and_grouping),
        "collection" to ui(R.string.ui_collection_time_window), "delivery" to ui(R.string.ui_email_and_webhook), "schedule" to ui(R.string.ui_days_intervals_and_time_zone),
        "secrets" to ui(R.string.ui_ai_keys_and_integration_tokens), "advanced" to ui(R.string.ui_extractors_and_display)) else emptyList()
    GlassSettingRow(ui(R.string.sources), ui(R.string.sources_description)) { open("sources") }
    GlassSettingRow(ui(R.string.control), ui(R.string.control_description)) { open("control") }
    pages.forEach { (page, subtitle) ->
        val title = when (page) { "profiles" -> ui(R.string.ui_processing_profiles); "delivery" -> ui(R.string.ui_delivery); "schedule" -> ui(R.string.ui_schedule)
            "secrets" -> ui(R.string.ui_secrets); "advanced" -> ui(R.string.ui_advanced_settings); else -> label(page) }
        GlassSettingRow(title, subtitle) { open(page) }
    }
    Tile {
        Text(ui(R.string.ui_connection), style = MaterialTheme.typography.titleMedium)
        var url by remember(server.id, server.https) { mutableStateOf(server.https) }
        var background by remember(server.id, server.background) { mutableStateOf(server.background) }
        ValueField(ui(R.string.ui_https_domain_optional), url, { url = it })
        Text(ui(R.string.ui_leave_blank_to_use_ssh_https))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(ui(R.string.ui_background_news_checks), Modifier.weight(1f))
            Switch(background, { background = it; if (it) permission() })
        }
        Text(ui(R.string.ui_for_faster_alerts_connect_ntfy_through))
        if (server.background) {
            TextButton(onClick = { chooseDistributor = true }) { Text(ui(R.string.ui_connect_push_notifications)) }
            if (registration.isNotEmpty()) {
                Text(ui(R.string.ui_push_notifications_connected))
                TextButton(onClick = {
                    DigestNotifications.unregister(context, server.id)
                    DigestWorker.sync(context, server.id)
                    pushMessage = ui(R.string.ui_push_notifications_disconnected)
                }) { Text(ui(R.string.ui_disconnect_push_notifications)) }
            }
        }
        if (pushMessage.isNotEmpty()) Text(pushMessage, style = MaterialTheme.typography.bodySmall)
        FlowRow {
            Button(onClick = { model.settings(url, background) }) { Text(ui(R.string.ui_save_connection)) }
            TextButton(onClick = ssh) { Text(ui(R.string.ui_ssh_settings)) }
        }
    }
    Tile {
        TextButton(onClick = { open("journal") }) { Text(ui(R.string.ui_journal)) }
        if (agentUpdateAvailable(panel.status.optString("agent")) || panel.updatingAgent) {
            TextButton(onClick = { upgrade = true }, enabled = !panel.busy && !panel.updatingAgent &&
                panel.jobs.none { it.optString("state") in setOf("queued", "running") }) { Text(ui(R.string.ui_update_vps_agent)) }
            if (panel.updatingAgent) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (config != null) Tile {
        Text(ui(R.string.ui_transfer_settings), style = MaterialTheme.typography.titleMedium)
        FlowRow {
            TextButton(onClick = { import("config") }) { Text(ui(R.string.ui_import_horizon_config)) }
            TextButton(onClick = { export("horizon-settings.json", config.toString(2)) }) { Text(ui(R.string.ui_export_settings)) }
            TextButton(onClick = { import("profiles") }) { Text(ui(R.string.ui_import_manager_profiles)) }
            TextButton(onClick = { export("horizon-profiles.json", panel.config.getJSONArray("profiles").toString(2)) }) { Text(ui(R.string.ui_export_profiles)) }
            TextButton(onClick = { confirm(ui(R.string.ui_restore_the_previous_configuration_and_its)) { model.revert() } }) { Text(ui(R.string.ui_roll_back_settings)) }
        }
        var path by remember { mutableStateOf(panel.probe["existing"].orEmpty()) }
        ValueField(ui(R.string.ui_existing_horizon_folder_on_the_vps), path, { path = it })
        TextButton(enabled = path.startsWith("/"), onClick = {
            confirm(ui(R.string.ui_copy_settings_from_this_folder_into)) { model.copyExisting(path) }
        }) { Text(ui(R.string.ui_copy_configuration_from_vps)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsForm(panel: Panel, model: ManagerModel, page: String, back: () -> Unit) {
    val stored = panel.config.optJSONObject("config") ?: return
    val serverId = panel.server?.id ?: return
    val schema = panel.schema.optJSONObject("config") ?: return
    val storedProfiles = panel.config.getJSONArray("profiles")
    var working by remember(page, panel.config.optInt("revision")) { mutableStateOf(stored.copyObject()) }
    var profiles by remember(page, panel.config.optInt("revision")) { mutableStateOf(JSONArray(storedProfiles.toString())) }
    val ids = profileIds(profiles)
    val fields = schema.getJSONObject("properties")
    val aiDraft = working.optJSONObject("ai")
    val secretAlias = aiDraft?.optString("api_key_env").orEmpty()
    val secrets = panel.config.optJSONArray("secrets")?.strings().orEmpty()
    val configuredSecret = secretAlias in secrets
    var genericKey by remember(secretAlias, aiDraft?.optString("provider"), panel.config.optInt("revision")) { mutableStateOf("") }
    var showGenericKey by remember(secretAlias, panel.config.optInt("revision")) { mutableStateOf(!configuredSecret) }
    Tile {
        when (page) {
            "schedule" -> ScheduleForm(panel, model)
            "secrets" -> {
                val secretNames = panel.schema.optJSONArray("secrets")?.strings().orEmpty()
                val configured = panel.config.optJSONArray("secrets")?.strings().orEmpty()
                val providerName = stored.getJSONObject("ai").optString("api_key_env")
                var name by remember { mutableStateOf(providerName.ifBlank { "OPENAI_API_KEY" }) }
                var value by remember { mutableStateOf("") }
                var remove by remember { mutableStateOf(false) }
                Text(ui(R.string.ui_secrets_on_the_vps), style = MaterialTheme.typography.titleLarge)
                Choice(ui(R.string.ui_known_variables), (secretNames + configured).distinct(), name, { name = it }, { it })
                ValueField(ui(R.string.ui_variable_name), name, { name = it })
                if (name in configured) Text(ui(R.string.ui_value_saved))
                PasswordField(ui(R.string.ui_new_value), value) { value = it }
                Button(enabled = value.isNotBlank() && name.isNotBlank() && !panel.busy, onClick = {
                    model.saveConfig(stored, storedProfiles, JSONObject().put(name, value))
                    value = ""
                }) { Text(ui(R.string.ui_save_secret)) }
                if (name in configured) TextButton(onClick = { remove = true }) { Text(ui(R.string.ui_delete_secret)) }
                if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text(ui(R.string.ui_delete, name)) },
                    text = { Text(ui(R.string.ui_sources_apis_or_delivery_that_rely)) },
                    confirmButton = { Button(onClick = { remove = false; model.saveConfig(stored, storedProfiles, JSONObject().put(name, JSONObject.NULL)) }) { Text(ui(R.string.ui_delete_86ea33)) } },
                    dismissButton = { TextButton(onClick = { remove = false }) { Text(ui(R.string.ui_cancel)) } })
            }
            "profiles" -> {
                Text(ui(R.string.ui_processing_profiles), style = MaterialTheme.typography.titleLarge)
                val root = panel.schema.getJSONObject("profile")
                var index by remember { mutableIntStateOf(0) }
                val current = profiles.optJSONObject(index)
                val choices = profiles.objects().mapIndexed { position, item -> "$position · ${item.getJSONObject("definition").optString("name")}" }
                if (choices.isNotEmpty()) Choice(ui(R.string.ui_profile_to_edit), choices, choices.getOrElse(index) { choices[0] },
                    { index = it.substringBefore(' ').toInt() }, { it.substringAfter(" · ") })
                current?.let {
                    val display = it.getJSONObject("definition")
                    key(index) {
                        EditValue(root, root, "profile_doc", it, { updated -> profiles = JSONArray(profiles.toString()).put(index, updated) },
                            ids, display.optString("id"), expandedByDefault = true)
                    }
                    FlowRow {
                        TextButton(onClick = {
                            val copy = it.copyObject()
                            val suffix = java.lang.Long.toString(System.currentTimeMillis(), 36)
                            copy.getJSONObject("definition").put("id", "profile-$suffix").put("name", ui(R.string.ui_copy_a40f6e, display.optString("name")))
                            profiles = JSONArray(profiles.toString()).put(copy)
                            index = profiles.length() - 1
                        }) { Text(ui(R.string.ui_duplicate_and_edit)) }
                        TextButton(enabled = profiles.length() > 1, onClick = {
                            profiles = JSONArray(profiles.toString()).apply { remove(index) }
                            index = (index - 1).coerceAtLeast(0)
                        }) { Text(ui(R.string.ui_remove)) }
                    }
                }
                EditValue(schema, fields.getJSONObject("processing"), "processing", working.getJSONObject("processing"),
                    { working = working.copyObject().put("processing", it) }, ids)
            }
            "ai" -> {
                val ai = working.getJSONObject("ai")
                val router = AiRouter.configured(ai)
                val provider = router?.id ?: ai.optString("provider")
                val providers = panel.schema.getJSONObject("providers")
                val supported = AiRouter.entries.map(AiRouter::id) +
                    providers.keys().asSequence().filter { it != "openai-chatgpt" }.toList()
                fun replace(value: JSONObject) { working = working.copyObject().put("ai", value) }
                Text(ui(R.string.ui_ai_provider), style = MaterialTheme.typography.titleLarge)
                Choice(ui(R.string.ui_provider), supported, provider, { change ->
                    if (change != provider) {
                        val preset = AiRouter.entries.find { it.id == change }
                        if (preset != null) replace(preset.defaults(ai))
                        else {
                            val defaults = providers.getJSONObject(change)
                            replace(ai.copyObject().put("provider", change).put("model", "")
                                .put("api_key_env", defaults.optString("api_key_env")).put("base_url", defaults.opt("base_url") ?: JSONObject.NULL)
                                .put("azure_endpoint_env", defaults.opt("azure_endpoint_env") ?: JSONObject.NULL)
                                .put("api_version", defaults.opt("api_version") ?: JSONObject.NULL))
                        }
                    }
                }, { value ->
                    AiRouter.entries.find { it.id == value }?.title
                        ?: if (value == "openai") ui(R.string.ui_openai_compatible_api) else value
                })
                if (router == null) {
                    ValueField(ui(R.string.ui_model_id), ai.optString("model"), { replace(ai.copyObject().put("model", it)) })
                    ValueField(ui(R.string.ui_compatible_api_address_optional), ai.optString("base_url").takeUnless { it == "null" }.orEmpty(), {
                        replace(ai.copyObject().put("base_url", it.ifBlank { null } ?: JSONObject.NULL))
                    })
                    if (provider != "ollama") {
                        if (configuredSecret) {
                            Text(ui(R.string.ui_key_saved_on_the_vps), style = MaterialTheme.typography.bodyMedium)
                            if (!showGenericKey) TextButton(onClick = { showGenericKey = true }) {
                                Text(ui(R.string.ui_replace_api_key))
                            }
                        }
                        if (showGenericKey) PasswordField(ui(R.string.ui_api_key, provider), genericKey) { genericKey = it }
                    }
                    Text(ui(R.string.ui_enter_the_model_id_from_your))
                    val advanced = definition(schema, fields.getJSONObject("ai")).copyObject()
                    advanced.getJSONObject("properties").remove("languages")
                    EditValue(schema, advanced, "ai", ai,
                        { working = working.copyObject().put("ai", it) }, ids, ui(R.string.ui_advanced_ai_options))
                } else {
                    val savedAlias = router.secretAlias(stored.getJSONObject("ai"))
                    val knownSecrets = panel.config.optJSONArray("secrets")?.strings().orEmpty()
                    val alias = savedAlias.takeIf { it in knownSecrets } ?: router.secretName
                    val hasKey = alias in knownSecrets
                    key(serverId, router, panel.config.optInt("revision")) {
                        RouterSettings(
                            router, ai.optString("model"), hasKey, panel.busy,
                            { key -> model.loadRouterCatalog(serverId, router, key) },
                            { id -> replace(ai.copyObject().put("model", id)) },
                        ) { newKey ->
                            val edited = ai.copyObject().put("api_key_env", alias)
                            val secret = JSONObject()
                            if (newKey != null) secret.put(alias, newKey)
                            model.saveConfig(working.copyObject().put("ai", edited), profiles, secret)
                        }
                    }
                    val advanced = definition(schema, fields.getJSONObject("ai")).copyObject()
                    val properties = advanced.getJSONObject("properties")
                    listOf("provider", "model", "api_key_env", "base_url", "azure_endpoint_env", "api_version", "provider_chain", "languages")
                        .forEach { properties.remove(it) }
                    EditValue(schema, advanced, "ai", ai,
                        { working = working.copyObject().put("ai", it) }, ids, ui(R.string.ui_advanced_ai_options))
                }
                val savedAi = stored.getJSONObject("ai")
                val canTest = savedAi.optString("model").isNotBlank() && (savedAi.optString("provider") == "ollama" ||
                    savedAi.optString("api_key_env") in panel.config.optJSONArray("secrets")?.strings().orEmpty())
                Text(ui(R.string.ui_the_reading_language_follows_android_new))
                TextButton(onClick = { model.submit("ai") }, enabled = !panel.busy && canTest) { Text(ui(R.string.ui_test_saved_model)) }
            }
            else -> {
                val names = when (page) { "delivery" -> listOf("email", "webhook"); "advanced" -> listOf("extractors", "display"); else -> listOf(page) }
                for (name in names) {
                    Text(label(name), style = MaterialTheme.typography.titleLarge)
                    EditValue(schema, fields.getJSONObject(name), name, working.opt(name),
                        { working = working.copyObject().put(name, it) }, ids, expandedByDefault = true)
                }
            }
        }
        if (page !in listOf("schedule", "secrets")) Row {
            if (page != "ai" || AiRouter.configured(working.getJSONObject("ai")) == null) {
                Button(onClick = {
                    val keys = JSONObject()
                    if (page == "ai" && genericKey.isNotBlank() && aiDraft?.optString("provider") != "ollama")
                        keys.put(secretAlias, genericKey.trim())
                    model.saveConfig(working, profiles, keys)
                }, enabled = !panel.busy) { Text(ui(R.string.ui_save_settings)) }
            }
            TextButton(onClick = back) { Text(ui(R.string.ui_back)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleForm(panel: Panel, model: ManagerModel) {
    val spec = panel.schedule.optJSONObject("spec") ?: return
    var mode by remember(spec.toString()) { mutableStateOf(spec.optString("mode")) }
    var hours by remember(spec.toString()) { mutableStateOf(spec.optInt("hours", 6).toString()) }
    var hour by remember(spec.toString()) { mutableStateOf(spec.optInt("hour", 8).toString()) }
    var minute by remember(spec.toString()) { mutableStateOf(spec.optInt("minute").toString()) }
    var zone by remember(spec.toString()) { mutableStateOf(spec.optString("zone")) }
    var days by remember(spec.toString()) {
        mutableStateOf(spec.optJSONArray("days")?.let { array -> (0 until array.length()).map { array.getInt(it) }.toSet() }.orEmpty())
    }
    Text(ui(R.string.ui_vps_schedule), style = MaterialTheme.typography.titleLarge)
    Choice(ui(R.string.ui_mode), listOf("off", "calendar", "interval"), mode, { mode = it },
        { mapOf("off" to ui(R.string.ui_off), "calendar" to ui(R.string.ui_by_day_of_the_week), "interval" to ui(R.string.ui_at_regular_intervals))[it] ?: it })
    if (mode == "interval") ValueField(ui(R.string.ui_interval_from_1_to_168_hours), hours, { hours = it }, numeric = true)
    if (mode == "calendar") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(ui(R.string.ui_mon), ui(R.string.ui_tue), ui(R.string.ui_wed), ui(R.string.ui_thu), ui(R.string.ui_fri), ui(R.string.ui_sat), ui(R.string.ui_sun)).forEachIndexed { day, title ->
                FilterChip(day in days, { days = if (day in days) days - day else days + day }, { Text(title) })
            }
        }
        ValueField(ui(R.string.ui_hour_0_23), hour, { hour = it }, numeric = true)
        ValueField(ui(R.string.ui_minute_0_59), minute, { minute = it }, numeric = true)
        Choice(ui(R.string.ui_time_zone), listOf("Asia/Yekaterinburg", "Europe/Moscow", "Europe/Amsterdam", "UTC").let { (it + zone).distinct() },
            zone, { zone = it }, { it })
        ValueField(ui(R.string.ui_other_iana_time_zone), zone, { zone = it })
    }
    val enabled = when (mode) {
        "interval" -> (hours.toIntOrNull() ?: 0) in 1..168
        "calendar" -> days.isNotEmpty() && (hour.toIntOrNull() ?: -1) in 0..23 && (minute.toIntOrNull() ?: -1) in 0..59 && zone.isNotBlank()
        else -> true
    }
    Button(enabled = enabled && !panel.busy, onClick = {
        model.schedule(JSONObject().put("mode", mode).apply {
            if (mode == "interval") put("hours", hours.toInt())
            if (mode == "calendar") put("hour", hour.toInt()).put("minute", minute.toInt()).put("zone", zone).put("days", JSONArray(days.sorted()))
        })
    }) { Text(ui(R.string.ui_save_schedule)) }
}
