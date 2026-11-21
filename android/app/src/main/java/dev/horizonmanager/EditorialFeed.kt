package dev.horizonmanager

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

@Composable
internal fun NewsScreen(
    panel: Panel,
    model: ManagerModel,
    issue: JSONObject?,
    filters: NewsFilters,
    archived: Boolean,
    ai: () -> Unit,
    sources: () -> Unit,
    back: () -> Unit,
    nav: (Boolean) -> Unit,
    focus: (Boolean) -> Unit,
) {
    val run = issue?.optString("id").orEmpty()
    val loaded = run.isNotEmpty() && panel.selectedRun == run && panel.markdown.isNotEmpty()
    val state = rememberLazyStaggeredGridState()
    var bubble by rememberSaveable(run) { mutableStateOf(false) }
    var full by rememberSaveable(run) { mutableStateOf(false) }
    var openId by rememberSaveable(run, panel.language) { mutableStateOf("") }
    val article =
        remember(panel.articles, loaded, openId) {
            panel.articles.find { loaded && it.optString("id") == openId }
        }
    val blocked = bubble || article != null
    SideEffect { focus(blocked) }
    DisposableEffect(Unit) {
        onDispose {
            focus(false)
            nav(true)
        }
    }
    BackHandler(bubble || full || filters.optionsOpen || filters.searchOpen) {
        when {
            bubble -> bubble = false
            full -> full = false
            filters.optionsOpen -> filters.optionsOpen = false
            else -> filters.searchOpen = false
        }
    }
    val flags = remember(panel.flags) { panel.flags.associateBy(ArticleFlag::article) }
    val incomplete = loaded && panel.articles.size < panel.total
    DisposableEffect(run, panel.language, incomplete) {
        val language = panel.language
        if (incomplete) model.loadChronology(run, language)
        onDispose { if (incomplete) model.cancelChronology(run, language) }
    }
    val query = filters.query.trim()
    val mode = filters.scope
    val category = filters.category
    val profile = filters.profile
    val order = filters.order
    val shown =
        remember(panel.articles, flags, query, mode, category, profile, order) {
            orderArticles(
                panel.articles.filter { item ->
                    val flag = flags[item.optString("id")]
                    (mode != NewsScope.Saved || flag?.saved == true) &&
                        (mode != NewsScope.Unread || flag?.read != true) &&
                        (category.isBlank() || articleField(item, "category") == category) &&
                        (profile.isBlank() || articleField(item, "profile") == profile) &&
                        (query.isBlank() ||
                            listOf("title", "summary", "source", "category").any {
                                articleField(item, it).contains(query, true)
                            } || telegramPublisher(item).contains(query, true))
                },
                order,
            )
        }
    val p = LocalHorizonPalette.current
    val preparing = !archived && digestIsPreparing(panel.jobs)
    val status =
        when {
            preparing -> ui(R.string.digest_preparing)
            panel.syncing -> ui(R.string.editorial_syncing)
            loaded && panel.syncedDigest == run && panel.connectionProblem.isEmpty() ->
                ui(R.string.editorial_current)
            loaded -> ui(R.string.editorial_cached)
            panel.connectionProblem.isNotEmpty() -> ui(R.string.editorial_not_available)
            else -> ui(R.string.issue_not_loaded)
        }
    val selectedNav by rememberUpdatedState(nav)
    val threshold = with(androidx.compose.ui.platform.LocalDensity.current) { 34.dp.toPx() }
    val scroll =
        remember(threshold) {
            object : NestedScrollConnection {
                var movement = 0f

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    val delta = consumed.y
                    if (movement * delta < 0) movement = 0f
                    movement += delta
                    if (kotlin.math.abs(movement) >= threshold) {
                        selectedNav(movement > 0)
                        movement = 0f
                    }
                    return Offset.Zero
                }
            }
        }
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0 }
            .collect { if (it) selectedNav(true) }
    }
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val scene = remember(layer) { GlassScene(layer) }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .imePadding()
    ) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val columns = if (maxWidth / fontScale < 295.dp) 1 else 2
        val controlsHeight = maxHeight * .45f
        Column(
            Modifier.fillMaxSize()
                .then(
                    if (article != null) Modifier.blur(3.dp).clearAndSetSemantics {} else Modifier
                )
        ) {
            val title = ui(if (archived) R.string.archive else R.string.feed)
            Row(
                Modifier.fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (archived || full)
                    GlassIconAction(Icons.AutoMirrored.Filled.ArrowBack, ui(R.string.ui_back)) {
                        if (full) full = false else back()
                    }
                val date = issue?.let { shortDigestDate(digestTime(it)) }.orEmpty()
                Text(
                    buildAnnotatedString {
                        append(title)
                        if (date.isNotBlank())
                            withStyle(
                                SpanStyle(
                                    fontWeight = FontWeight.Light,
                                    color = p.muted,
                                    fontSize = 27.sp,
                                )
                            ) {
                                append(" " + date)
                            }
                    },
                    Modifier.weight(1f)
                        .clickable(role = Role.Button) { bubble = !bubble }
                        .heightIn(min = 48.dp)
                        .padding(vertical = 5.dp),
                    style = MaterialTheme.typography.displaySmall,
                )
                if (!bubble && !full) NewsToolbar(filters, loaded)
            }
            if (!bubble && !full && loaded) {
                PinnedNewsControls(filters, panel.articles, controlsHeight)
            }
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize()
                        .recordScene(scene)
                        .then(if (bubble) Modifier.blur(3.dp).clearAndSetSemantics {} else Modifier)
                ) {
                    if (full && loaded) {
                        val blocks = remember(panel.markdown) { markdownBlocks(panel.markdown) }
                        LazyColumn(
                            contentPadding =
                                PaddingValues(
                                    start = 18.dp,
                                    end = 18.dp,
                                    top = 6.dp,
                                    bottom = 106.dp,
                                )
                        ) {
                            items(blocks.size) { i -> MarkdownBlock(blocks[i]) }
                        }
                    } else {
                        LazyVerticalStaggeredGrid(
                            StaggeredGridCells.Fixed(columns),
                            state = state,
                            modifier = Modifier.fillMaxSize().nestedScroll(scroll),
                            contentPadding =
                                PaddingValues(
                                    start = 12.dp,
                                    end = 12.dp,
                                    top = 4.dp,
                                    bottom =
                                        WindowInsets.navigationBars
                                            .asPaddingValues()
                                            .calculateBottomPadding() + 90.dp,
                                ),
                            verticalItemSpacing = 9.dp,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            if (preparing || panel.syncing && !loaded)
                                item("progress", span = StaggeredGridItemSpan.FullLine) {
                                    Column {
                                        Text(status, style = MaterialTheme.typography.labelLarge)
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                }
                            if (!loaded || shown.isEmpty())
                                item("empty", span = StaggeredGridItemSpan.FullLine) {
                                    Tile {
                                        Text(
                                            if (loaded)
                                                ui(
                                                    if (panel.total == 0)
                                                        R.string
                                                            .ui_no_items_passed_the_horizon_filters
                                                    else
                                                        R.string
                                                            .ui_no_downloaded_articles_match_your_filters
                                                )
                                            else if (issue == null)
                                                ui(R.string.ui_news_will_appear_here_after_horizon)
                                            else status
                                        )
                                        TextButton(onClick = { bubble = true }) {
                                            Text(ui(R.string.mr_details))
                                        }
                                    }
                                }
                            if (loaded)
                                items(
                                    shown.size,
                                    key = { "story:" + shown[it].getString("id") },
                                    contentType = { "story" },
                                ) { index ->
                                    val item = shown[index]
                                    val id = item.getString("id")
                                    val flag = flags[id]
                                    PublicationTile(
                                        item,
                                        flag,
                                        Modifier.semantics { traversalIndex = index.toFloat() },
                                        artworkServer = panel.server?.id.orEmpty(),
                                        relay = model.coverRelay(panel, id),
                                        open = {
                                            openId = id
                                            if (flag?.read != true) model.flag(id, read = true)
                                        },
                                    ) {
                                        model.flag(id, save = flag?.saved != true)
                                    }
                                }
                            if (incomplete && panel.problem.isNotEmpty())
                                item("retry", span = StaggeredGridItemSpan.FullLine) {
                                    OutlinedButton(
                                        onClick = { model.loadChronology(run, panel.language) }
                                    ) {
                                        Text(ui(R.string.ui_retry))
                                    }
                                }
                        }
                    }
                }
                if (blocked)
                    Box(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .34f)).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            bubble = false
                        }
                    )
                androidx.compose.animation.AnimatedVisibility(
                    bubble,
                    enter =
                        androidx.compose.animation.fadeIn() +
                            androidx.compose.animation.expandVertically(),
                    exit =
                        androidx.compose.animation.fadeOut() +
                            androidx.compose.animation.shrinkVertically(),
                ) {
                    CompositionLocalProvider(LocalGlassScene provides scene) {
                        DigestBubble(
                            issue,
                            if (loaded) panel.total else null,
                            status,
                            preparing || panel.syncing,
                            !panel.busy && !panel.syncing,
                            { model.refresh() },
                            loaded,
                            {
                                bubble = false
                                full = true
                            },
                            archived,
                            panel.jobs.none {
                                it.optString("state") in setOf("running", "queued")
                            } && !panel.busy,
                            { model.submit() },
                            {
                                bubble = false
                                model.archiveDigest(run)
                            },
                            loaded && issue?.has("output_language") == false &&
                                issue.optJSONArray("languages")?.strings()?.contains(panel.preferredDigestLanguage()) == false,
                            { model.translateDigest(run) },
                            if (panel.archive.isEmpty()) ai else null,
                            sources,
                        )
                    }
                }
            }
        }
        AnimatedContent(
            targetState = article,
            modifier = Modifier.fillMaxSize(),
            contentKey = { it?.optString("id") },
            transitionSpec = {
                (fadeIn(tween(180)) + scaleIn(tween(230), initialScale = .965f)) togetherWith
                    (fadeOut(tween(125)) + scaleOut(tween(145), targetScale = .985f))
            },
            label = "open publication",
        ) { selected ->
            if (selected != null) {
                val id = selected.getString("id")
                val flag = flags[id]
                val serverId = panel.server?.id.orEmpty()
                val run = panel.selectedRun
                val language = panel.language
                ArticleDialog(
                    selected, flag, { model.flag(id, save = flag?.saved != true) },
                    { model.flag(id, read = flag?.read != true) },
                    artworkServer = serverId,
                    relay = model.coverRelay(panel, id),
                    contextContent = {
                        key(serverId, run, language, id) {
                            ArticleContext(selected) { submit ->
                                model.getArticleContext(serverId, run, id, language, submit)
                            }
                        }
                    },
                ) { openId = "" }
            }
        }
    }
}

internal fun shortDigestDate(value: String): String =
    runCatching {
            DateTimeFormatter.ofPattern("dd.MM")
                .format(Instant.parse(value).atZone(ZoneId.systemDefault()))
        }
        .getOrDefault("")

@Composable
internal fun DigestBubble(
    issue: JSONObject?,
    count: Int?,
    status: String,
    busy: Boolean,
    syncEnabled: Boolean,
    sync: () -> Unit,
    loaded: Boolean,
    details: () -> Unit,
    archived: Boolean,
    canRun: Boolean,
    generate: () -> Unit,
    archive: () -> Unit,
    canTranslate: Boolean,
    translate: () -> Unit,
    ai: (() -> Unit)?,
    sources: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val p = LocalHorizonPalette.current
    Column(
        Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            .widthIn(max = 455.dp)
            .fillMaxWidth()
            .frost(MaterialTheme.shapes.medium)
            .pointerInput(Unit) { detectTapGestures { } }
            .semantics { paneTitle = ui(R.string.mr_details) }
            .padding(start = 14.dp, end = 6.dp, top = 9.dp, bottom = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                ImageVector.vectorResource(R.drawable.ic_archive),
                null,
                Modifier.size(23.dp),
                tint = p.link,
            )
            Text(
                ui(if (archived) R.string.mr_archive_issue else R.string.mr_latest),
                Modifier.weight(1f).padding(start = 9.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            Box {
                GlassIconAction(Icons.Default.MoreVert, ui(R.string.editorial_digest_actions)) {
                    menu = true
                }
                DropdownMenu(menu, { menu = false }) {
                    if (!archived) {
                        DropdownMenuItem(
                            { Text(ui(R.string.ui_start_collection)) },
                            {
                                menu = false
                                generate()
                            },
                            enabled = canRun,
                        )
                        if (issue != null)
                            DropdownMenuItem(
                                { Text(ui(R.string.archive_early)) },
                                {
                                    menu = false
                                    archive()
                                },
                            )
                    }
                    if (canTranslate)
                        DropdownMenuItem(
                            { Text(ui(R.string.ui_translate_issue)) },
                            {
                                menu = false
                                translate()
                            },
                            enabled = canRun,
                        )
                    if (ai != null) {
                        DropdownMenuItem(
                            { Text(ui(R.string.ui_configure_ai)) },
                            {
                                menu = false
                                ai()
                            },
                        )
                        DropdownMenuItem(
                            { Text(ui(R.string.ui_add_a_source)) },
                            {
                                menu = false
                                sources()
                            },
                        )
                    }
                    if (archived && !canTranslate)
                        DropdownMenuItem(
                            { Text(ui(R.string.editorial_open_digest)) },
                            {
                                menu = false
                                details()
                            },
                            enabled = loaded,
                        )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                issue?.let {
                    Text(localTime(digestTime(it)), style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    listOfNotNull(count?.let { ui(R.string.editorial_article_count, it) }, status)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = p.muted,
                )
            }
            GlassIconAction(Icons.Filled.Refresh, ui(R.string.ui_sync), syncEnabled, onClick = sync)
            GlassIconAction(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                ui(R.string.editorial_open_digest),
                loaded,
                onClick = details,
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
internal fun PublicationTile(
    article: JSONObject,
    flag: ArticleFlag?,
    modifier: Modifier,
    artworkServer: String,
    relay: (suspend () -> ByteArray?)? = null,
    open: () -> Unit,
    save: () -> Unit,
) {
    val key = article.optString("id")
    val cover = artwork(artworkServer, article, relay)
    val photoHeight = 112.dp
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(19.dp)
    Box(modifier.fillMaxWidth().clip(shape).clickable(role = Role.Button, onClick = open)) {
        CoverSurface(cover, key, Modifier.matchParentSize(), photoHeight = photoHeight)
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 9.dp, end = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val category = if (isTelegram(article)) "Telegram"
                    else articleField(article, "category").ifBlank { articleField(article, "source") }
                if (category.isNotBlank())
                    Text(
                        category,
                        Modifier.weight(1f, fill = false)
                            .background(
                                Color.Black.copy(alpha = .45f),
                                androidx.compose.foundation.shape.CircleShape,
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                else Spacer(Modifier.weight(1f))
                val haptic = LocalHapticFeedback.current
                GlassIconAction(
                    if (flag?.saved == true) Icons.Default.Star
                    else ImageVector.vectorResource(R.drawable.ic_star_outline),
                    ui(
                        if (flag?.saved == true) R.string.remove_from_favorites
                        else R.string.add_to_favorites
                    ),
                    active = flag?.saved == true,
                ) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    save()
                }
            }
            Spacer(Modifier.height(photoHeight - 48.dp))
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 14.dp, top = 9.dp)
            ) {
                Text(
                    articleField(article, "title"),
                    color = if (flag?.read == true) Color(0xffd7d9da) else Color.White,
                    style =
                        MaterialTheme.typography.titleLarge.copy(
                            letterSpacing = (-.55).sp,
                            lineBreak = LineBreak.Paragraph,
                            platformStyle = PlatformTextStyle(includeFontPadding = true),
                        ),
                )
                val source = if (isTelegram(article)) telegramPublisher(article) else articleField(article, "source")
                if (source.isNotBlank())
                    Text(
                        source,
                        Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xffd1d3d5),
                    )
            }
        }
    }
}

internal fun articleSource(article: JSONObject): String =
    listOf(
            articleField(article, "source").takeIf(String::isNotBlank)?.let(::label).orEmpty(),
            telegramPublisher(article),
            articleField(article, "category"),
            articleField(article, "profile"),
        )
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .joinToString(" · ")

private val telegramPost = Regex("/(?:s/)?([A-Za-z][A-Za-z0-9_]{3,31})/[0-9]+/?")
private val telegramHosts = setOf("t.me", "telegram.me", "telegram.dog")

private fun isTelegram(article: JSONObject) = articleField(article, "source").equals("telegram", true)

internal fun telegramPublisher(article: JSONObject): String {
    if (!isTelegram(article)) return ""
    normalizeTelegramChannel(articleField(article, "telegram_channel"))?.let { return "@$it" }
    val mediaSource = article.optJSONObject("media")?.let { articleField(it, "source") }.orEmpty()
    for (link in listOf(articleField(article, "telegram_url"), articleField(article, "url"), mediaSource)) {
        val uri = runCatching { java.net.URI(link) }.getOrNull() ?: continue
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http") ||
            uri.host?.lowercase(Locale.ROOT) !in telegramHosts || uri.userInfo != null) continue
        val channel = telegramPost.matchEntire(uri.rawPath.orEmpty())?.groupValues?.get(1) ?: continue
        return "@$channel"
    }
    return ""
}

internal fun articleMetadata(article: JSONObject): String =
    listOf(
            localTime(articleField(article, "time")),
            article
                .optDouble("score")
                .takeIf(Double::isFinite)
                ?.let { "%.1f / 10".format(appLocale(), it) }
                .orEmpty(),
        )
        .filter(String::isNotBlank)
        .joinToString(" · ")

@Composable
internal fun ArchiveScreen(
    issues: List<JSONObject>,
    panel: Panel,
    model: ManagerModel,
    open: (JSONObject) -> Unit,
    header: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val local by
        remember(panel.server?.id) {
                ManagerDb.get(context).cache().localIssues(panel.server?.id.orEmpty())
            }
            .collectAsState(initial = emptyList())
    var date by rememberSaveable { mutableStateOf("") }
    val byMonth =
        remember(issues, date) {
            issues
                .filter {
                    date.isBlank() ||
                        digestTime(it).contains(date.trim()) ||
                        localTime(digestTime(it)).contains(date.trim(), true)
                }
                .groupBy { issue ->
                    runCatching {
                            Instant.parse(digestTime(issue))
                                .atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("MMMM yyyy"))
                        }
                        .getOrDefault("")
                }
        }
    val theme = LocalHorizonPalette.current
    LazyColumn(
        Modifier.fillMaxSize().editorialInsets(),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 90.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("header") { header() }
        item("summary") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    ui(
                        if (panel.manuallyArchived.isEmpty()) R.string.archive_period
                        else R.string.archive_period_with_manual,
                        panel.collectionHours,
                    ),
                    color = theme.muted,
                )
                if (panel.connectionProblem.isNotBlank()) {
                    Text(
                        ui(
                            if (issues.isEmpty()) R.string.editorial_not_available
                            else R.string.editorial_cached
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.warning,
                    )
                    OutlinedButton(onClick = { model.refresh() }, enabled = !panel.busy) {
                        Text(ui(R.string.ui_retry))
                    }
                }
                if (panel.syncing || panel.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (issues.size > 7 || date.isNotEmpty())
                    ValueField(ui(R.string.editorial_archive_search), date, { date = it })
                if (byMonth.isEmpty() && !panel.busy)
                    Text(
                        ui(
                            if (date.isBlank()) R.string.empty_archive
                            else R.string.editorial_no_results
                        )
                    )
            }
        }
        for ((month, entries) in byMonth) {
            item("month:$month") {
                Text(
                    month,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            items(entries, key = { it.getString("id") }) { issue ->
                val id = issue.getString("id")
                val count = issue.optInt("total", if (panel.selectedRun == id) panel.total else -1)
                val details =
                    listOfNotNull(
                            count
                                .takeIf { it >= 0 }
                                ?.let { ui(R.string.editorial_article_count, it) },
                            if (id in panel.manuallyArchived)
                                ui(R.string.editorial_manually_archived)
                            else null,
                            ui(if (id in local) R.string.mr_offline_copy else R.string.mr_on_server),
                        )
                        .joinToString(" · ")
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) { open(issue) }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        shortDigestDate(digestTime(issue)),
                        style = MaterialTheme.typography.headlineMedium,
                        color = theme.link,
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(
                            localTime(digestTime(issue)),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            details.ifBlank { ui(R.string.editorial_open_digest) },
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.muted,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        ui(R.string.editorial_open_digest),
                    )
                }
                HorizontalDivider(color = theme.card)
            }
        }
        if (panel.hasMoreDigests)
            item("more") {
                OutlinedButton(onClick = model::loadMoreDigests, enabled = !panel.busy) {
                    Text(ui(R.string.older_issues))
                }
            }
    }
}

@Composable
internal fun Modifier.editorialInsets(): Modifier =
    windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        )
        .navigationBarsPadding()
