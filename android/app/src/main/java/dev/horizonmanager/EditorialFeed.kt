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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
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
    var bubble by rememberSaveable(panel.server?.id, run) { mutableStateOf(false) }
    var full by rememberSaveable(run) { mutableStateOf(false) }
    val serverId = panel.server?.id.orEmpty()
    val notices = LocalNoticeQueue.current?.messages
    val scope = rememberCoroutineScope()
    fun archiveCurrent() {
        if (model.archiveDigest(serverId, run)) {
            scope.launch { notices?.showSnackbar(SavedNotice(ui(R.string.hovq_digest_archived))) }
        }
    }
    var openId by rememberSaveable(serverId, run, panel.language) { mutableStateOf("") }
    var openedUnread by rememberSaveable(serverId, run, panel.language) { mutableStateOf(false) }
    var archiveAfterReading by rememberSaveable(serverId, run, panel.language) { mutableStateOf(false) }
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
    val allRead = remember(loaded, panel.total, panel.articles, flags) {
        loaded && panel.total > 0 && panel.articles.size == panel.total &&
            panel.articles.all { flags[it.optString("id")]?.read == true }
    }
    if (!archived && archiveAfterReading && allRead && openId.isEmpty() && !promptOpen) {
        HorizonAlertDialog(
            onDismissRequest = { archiveAfterReading = false },
            title = { Text(ui(R.string.archive_read_digest_title)) },
            text = { Text(ui(R.string.archive_read_digest_detail)) },
            confirmButton = {
                TextButton(onClick = {
                    archiveAfterReading = false
                    archiveCurrent()
                }) { Text(ui(R.string.archive_read_digest_accept)) }
            },
            dismissButton = {
                TextButton(onClick = { archiveAfterReading = false }) { Text(ui(R.string.archive_read_digest_later)) }
            },
        )
    }
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
                        if (issue == null && !archived)
                            item("empty-feed", span = StaggeredGridItemSpan.FullLine) {
                                EmptyDigestFeed(panel) { model.submit() }
                            }
                        else if (!loaded || shown.isEmpty())
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
                                        archiveAfterReading = false
                                        openedUnread = flag?.read != true
                                        openId = id
                                        if (openedUnread) model.flag(item, read = true)
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
                            archiveCurrent()
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
            StatusGlass(extendIntoFeed = true)
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
            val run = panel.selectedRun
            val language = panel.language
            ArticleDialog(
                selected, flag, { model.flag(selected, save = flag?.saved != true) },
                {
                    val read = flag?.read != true
                    openedUnread = read
                    model.flag(selected, read = read)
                },
                artworkServer = serverId,
                displayOptions = panel.status.optJSONObject("article_options") ?: model.articleDisplayOptions(serverId),
                relay = model.coverRelay(panel, id),
                contextContent = {
                    key(serverId, run, language, id) {
                        ArticleContext(selected) { submit ->
                            model.getArticleContext(serverId, run, id, language, submit)
                        }
                    }
                },
            ) {
                archiveAfterReading = !archived && openedUnread
                openedUnread = false
                openId = ""
            }
        }
    }
}

@Composable
private fun EmptyDigestFeed(panel: Panel, prepare: () -> Unit) {
    val mode = panel.schedule.optJSONObject("spec")?.optString("mode")
    val next = panel.schedule.optDouble("next", panel.status.optDouble("next"))
    val nextTime = when {
        mode == "off" -> ui(R.string.schedule_manual)
        !next.isFinite() || next <= 0 -> ui(R.string.schedule_unavailable)
        else -> localEpoch(next)
    }
    val enabled = panel.ready && !panel.busy && !panel.updatingAgent &&
        panel.jobs.none { it.optString("state") in setOf("running", "queued") }
    Column(Modifier.fillMaxWidth().glassSurface(MaterialTheme.shapes.extraLarge, raised = true)
        .padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(ui(if (panel.connectionProblem.isEmpty()) R.string.schedule_next else R.string.schedule_last_known),
            color = LocalHorizonPalette.current.muted, style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center)
        Text(nextTime, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(ui(R.string.prepare_digest_now),
            modifier = Modifier.padding(top = 5.dp).heightIn(min = 48.dp).glassSurface(CircleShape)
                .then(if (enabled) Modifier.savedNoticeWash() else Modifier)
                .clickable(enabled = enabled, role = Role.Button, onClick = prepare)
                .wrapContentHeight().padding(horizontal = 20.dp, vertical = 12.dp),
            color = if (enabled) Color.White else LocalHorizonPalette.current.muted,
            style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FloatingFeedHeader(issue: JSONObject?, filters: NewsFilters, loaded: Boolean,
                               archived: Boolean, bubble: Boolean, full: Boolean,
                               modifier: Modifier, back: () -> Unit, toggleBubble: () -> Unit) {
    val title = ui(if (archived) R.string.archive else R.string.feed)
    val date = issue?.let { shortDigestDate(digestTime(it)) }.orEmpty()
    Row(
        modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 5.dp, bottom = 4.dp)
            .height(readingBarHeight()).readingNavigationGlass(feedGlassShape).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (archived || full) {
            IconButton(onClick = back, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, ui(R.string.ui_back), tint = ReadingGlass.text)
            }
        }
        Text(
            buildAnnotatedString {
                append(title)
                if (date.isNotBlank()) withStyle(SpanStyle(
                    fontWeight = FontWeight.Light, color = ReadingGlass.muted,
                )) { append(" " + date) }
            },
            Modifier.weight(1f).clickable(interactionSource = null, indication = ripple(color = ReadingGlass.text),
                role = Role.Button, onClick = toggleBubble)
                .heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).padding(vertical = 5.dp),
            style = readingBarTitleStyle(),
            maxLines = 1, overflow = TextOverflow.Ellipsis, color = ReadingGlass.text,
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
    CompositionLocalProvider(LocalLightGlass provides true) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                .widthIn(max = 455.dp)
                .fillMaxWidth()
                .readingNavigationGlass(feedGlassShape)
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { paneTitle = ui(R.string.mr_details) }
                .padding(start = 14.dp, end = 6.dp, top = 9.dp, bottom = 9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    ImageVector.vectorResource(R.drawable.ic_archive),
                    null,
                    Modifier.size(23.dp),
                    tint = ReadingGlass.text,
                )
                Text(
                    ui(if (archived) R.string.mr_archive_issue else R.string.mr_latest),
                    Modifier.weight(1f).padding(start = 9.dp),
                    style = MaterialTheme.typography.titleLarge, color = ReadingGlass.text,
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
                        Text(localTime(digestTime(it)), style = MaterialTheme.typography.labelLarge, color = ReadingGlass.text)
                    }
                    Text(
                        listOfNotNull(count?.let { ui(R.string.editorial_article_count, it) }, status)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = ReadingGlass.muted,
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
                Modifier.fillMaxWidth().padding(end = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                val haptic = LocalHapticFeedback.current
                CoverIconAction(
                    ImageVector.vectorResource(if (flag?.saved == true) R.drawable.ic_bookmark_filled
                        else R.drawable.ic_bookmark_outline),
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
                val metadataHeight = with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = metadataHeight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PublicationSource(article, Modifier.weight(1f))
                    PublicationScore(article)
                }
            }
        }
        Spacer(Modifier.matchParentSize().publicationGlassRim(shape))
        if (unread || showBorder) {
            Spacer(Modifier.matchParentSize().graphicsLayer()
                .unreadOutline(shape, key, borderAlpha, movingBorder))
        }
    }
}

private val telegramPost = Regex("/(?:s/)?([A-Za-z][A-Za-z0-9_]{3,31})/[0-9]+/?")
private val telegramHosts = setOf("t.me", "telegram.me", "telegram.dog")

internal fun isTelegram(article: JSONObject) = articleField(article, "source").equals("telegram", true)

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

@Composable
internal fun ArchiveScreen(
    issues: List<JSONObject>,
    panel: Panel,
    model: ManagerModel,
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
    LazyColumn(
        Modifier.fillMaxSize().editorialSideBottomInsets(),
        state = rememberLazyListState(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("summary") {
            Tile {
                SettingNote(ui(R.string.hovq_archive_visibility, panel.collectionHours))
                if (panel.connectionProblem.isNotBlank()) {
                    Text(
                        ui(
                            if (issues.isEmpty()) R.string.editorial_not_available
                            else R.string.editorial_cached
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
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
                    Modifier.fillMaxWidth().settingsControlGlass()
                        .clickable(role = Role.Button) { open(issue) }
                        .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        shortDigestDate(digestTime(issue)),
                        style = MaterialTheme.typography.headlineMedium,
                        color = ReadingGlass.text,
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(
                            localTime(digestTime(issue)),
                            style = MaterialTheme.typography.titleSmall, color = ReadingGlass.text,
                        )
                        Text(
                            details.ifBlank { ui(R.string.editorial_open_digest) },
                            style = MaterialTheme.typography.bodySmall,
                            color = ReadingGlass.muted,
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
