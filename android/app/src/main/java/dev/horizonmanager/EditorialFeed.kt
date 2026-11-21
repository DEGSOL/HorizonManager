package dev.horizonmanager

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.Dp
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
    promptOpen: Boolean,
    ai: () -> Unit,
    sources: () -> Unit,
    back: () -> Unit,
    nav: (Boolean) -> Unit,
    focus: (ReadingOverlay) -> Unit,
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
    SideEffect {
        focus(when {
            article != null -> ReadingOverlay.Article
            bubble -> ReadingOverlay.Digest
            else -> ReadingOverlay.None
        })
    }
    DisposableEffect(Unit) {
        onDispose {
            focus(ReadingOverlay.None)
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
    var barsRevealed by rememberSaveable(run, archived) { mutableStateOf(true) }
    val controlsOpen = filters.searchOpen || filters.optionsOpen || bubble || full
    val barsVisible = barsRevealed || controlsOpen
    SideEffect { nav(barsVisible) }
    val density = LocalDensity.current
    val threshold = with(density) { 34.dp.toPx() }
    val scroll =
        remember(run, archived, threshold) {
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
                        barsRevealed = movement > 0
                        movement = 0f
                    }
                    return Offset.Zero
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    movement = 0f
                    return Velocity.Zero
                }
            }
        }
    LaunchedEffect(state, run) {
        snapshotFlow { !state.canScrollBackward }
            .collect { if (it) barsRevealed = true }
    }
    LaunchedEffect(controlsOpen) { if (controlsOpen) barsRevealed = true }
    var headerHeight by remember { mutableStateOf(69.dp) }
    val movingBorder = rememberUnreadBorderClock {
        !full && !blocked && !promptOpen && state.layoutInfo.visibleItemsInfo.any { visible ->
            val key = visible.key as? String ?: return@any false
            key.startsWith("story:") && flags[key.removePrefix("story:")]?.read != true
        }
    }
    val layer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val scene = remember(layer) { GlassScene(layer) }
    val topInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
            )
            .imePadding()
            .clip(androidx.compose.ui.graphics.RectangleShape)
    ) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        val columns = if (maxWidth / fontScale < 295.dp) 1 else 2
        val controlsHeight = (maxHeight - topInset).coerceAtLeast(0.dp) * .45f
        Box(Modifier.fillMaxSize().then(
            if (article != null) Modifier.clearAndSetSemantics {} else Modifier
        )) {
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
                                top = topInset + headerHeight + 6.dp,
                                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                    LocalNavigationClearance.current,
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
                                top = topInset + headerHeight + 4.dp,
                                bottom =
                                    WindowInsets.navigationBars
                                        .asPaddingValues()
                                        .calculateBottomPadding() + (if (archived) 12.dp else LocalNavigationClearance.current),
                            ),
                        verticalItemSpacing = 9.dp,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        if (preparing || panel.syncing && !loaded)
                            item("progress", span = StaggeredGridItemSpan.FullLine) {
                                Column {
                                    Text(status, style = MaterialTheme.typography.labelLarge)
                                    DottedProgress(Modifier.fillMaxWidth())
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
                                    movingBorder = movingBorder,
                                    relay = model.coverRelay(panel, id),
                                    open = {
                                        openId = id
                                        if (flag?.read != true) model.flag(item, read = true)
                                    },
                                ) {
                                    model.flag(item, save = flag?.saved != true)
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
            if (bubble)
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = .34f)).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        bubble = false
                    }
                )
            AnimatedVisibility(
                bubble,
                modifier = Modifier.padding(top = topInset + headerHeight),
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
        CompositionLocalProvider(LocalGlassScene provides scene) {
            AnimatedVisibility(
                barsVisible,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topInset),
                enter = fadeIn(tween(navigationSlideMillis)) + slideInVertically(tween(navigationSlideMillis)) { -it },
                exit = fadeOut(tween(navigationSlideMillis)) + slideOutVertically(tween(navigationSlideMillis)) { -it },
            ) {
                Column(Modifier.fillMaxWidth()) {
                    FloatingFeedHeader(issue, filters, loaded, archived, bubble, full,
                        Modifier.onSizeChanged { size -> headerHeight = with(density) { size.height.toDp() } },
                        back = { if (full) full = false else back() },
                        toggleBubble = { bubble = !bubble },
                    )
                    if (!bubble && !full && loaded) {
                        PinnedNewsControls(filters, panel.articles, controlsHeight)
                    }
                }
            }
        }
        article?.let { selected ->
            val id = selected.getString("id")
            val flag = flags[id]
            val serverId = panel.server?.id.orEmpty()
            val run = panel.selectedRun
            val language = panel.language
            ArticleDialog(
                selected, flag, { model.flag(selected, save = flag?.saved != true) },
                { model.flag(selected, read = flag?.read != true) },
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

@Composable
private fun FloatingFeedHeader(issue: JSONObject?, filters: NewsFilters, loaded: Boolean,
                               archived: Boolean, bubble: Boolean, full: Boolean,
                               modifier: Modifier, back: () -> Unit, toggleBubble: () -> Unit) {
    val title = ui(if (archived) R.string.archive else R.string.feed)
    val date = issue?.let { shortDigestDate(digestTime(it)) }.orEmpty()
    val palette = LocalHorizonPalette.current
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 5.dp, bottom = 4.dp)
            .height(readingBarHeight()).navigationGlass(feedGlassShape).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (archived || full) {
            IconButton(onClick = back, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, ui(R.string.ui_back), tint = palette.text)
            }
        }
        Text(
            buildAnnotatedString {
                append(title)
                if (date.isNotBlank()) withStyle(SpanStyle(
                    fontWeight = FontWeight.Light, color = palette.muted,
                )) { append(" " + date) }
            },
            Modifier.weight(1f).clickable(role = Role.Button, onClick = toggleBubble)
                .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).padding(vertical = 5.dp),
            style = if (LocalConfiguration.current.screenWidthDp < 350) MaterialTheme.typography.headlineMedium
                else MaterialTheme.typography.displaySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = palette.text,
        )
        if (!bubble && !full) NewsToolbar(filters, loaded)
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
            .navigationGlass(MaterialTheme.shapes.medium)
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
                GlassDropdownMenu(menu, { menu = false }) {
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
        if (busy) DottedProgress(Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
internal fun PublicationTile(
    article: JSONObject,
    flag: ArticleFlag?,
    modifier: Modifier,
    artworkServer: String,
    relay: (suspend () -> ByteArray?)? = null,
    movingBorder: State<BorderWaveFrame>? = null,
    open: () -> Unit,
    save: () -> Unit,
) {
    val key = article.optString("id")
    val cover = artwork(artworkServer, article, relay)
    val photoHeight = 112.dp
    val shape = RoundedCornerShape(19.dp)
    val unread = flag?.read != true
    val borderAlpha = androidx.compose.animation.core.animateFloatAsState(
        if (unread) 1f else 0f, tween(280), label = "Unread outline opacity",
    )
    val showBorder by remember { derivedStateOf { borderAlpha.value > 0f } }
    val unreadLabel = ui(R.string.ui_unread)
    PublicationCover(cover, key, modifier.fillMaxWidth().clip(shape)
        .semantics { if (unread) stateDescription = unreadLabel }
        .clickable(role = Role.Button, onClick = open), photoHeight = photoHeight) {
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
                CoverIconAction(
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
        if (unread || showBorder) {
            Spacer(Modifier.matchParentSize().graphicsLayer()
                .unreadOutline(shape, key, borderAlpha, movingBorder, cover?.luminance, photoHeight))
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
    top: Dp,
    open: (JSONObject) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val local by
        remember(panel.server?.id) {
                ManagerDb.get(context).cache().localIssues(panel.server?.id.orEmpty())
            }
            .collectAsState(initial = emptyList())
    var date by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf("") }
    var pendingRemoval by rememberSaveable { mutableStateOf("") }
    issues.find { it.optString("id") == pendingRemoval }?.let { issue ->
        HorizonAlertDialog(onDismissRequest = { pendingRemoval = "" },
            title = { Text(ui(R.string.archive_remove_device)) },
            text = { Text(ui(R.string.archive_remove_detail, localTime(digestTime(issue)))) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = ""
                    model.deleteArchive(issue.getString("id"))
                }, enabled = !panel.busy) { Text(ui(R.string.archive_remove_device)) }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = "" }) { Text(ui(R.string.ui_cancel)) } })
    }
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
        Modifier.fillMaxSize().editorialSideBottomInsets(),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                if (panel.syncing || panel.busy) DottedProgress(Modifier.fillMaxWidth())
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
                    Box {
                        GlassIconAction(Icons.Default.MoreVert, ui(R.string.archive_issue_actions)) { menu = id }
                        GlassDropdownMenu(expanded = menu == id, onDismissRequest = { menu = "" }) {
                            DropdownMenuItem(
                                text = { Text(ui(R.string.archive_remove_device)) },
                                enabled = !panel.busy,
                                onClick = { menu = ""; pendingRemoval = id },
                            )
                        }
                    }
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

@Composable
internal fun Modifier.editorialSideBottomInsets(): Modifier =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
