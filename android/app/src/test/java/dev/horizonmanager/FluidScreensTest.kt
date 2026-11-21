package dev.horizonmanager

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ru-rRU-w412dp-h860dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FluidScreensTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val photos = listOf("earth", "as11-40-5875", "iss042e013697", "iss036e030926")
    private val headlines =
        listOf(
            "Рассвет над горизонтом Земли",
            "На поверхности Луны",
            "Как выглядит Земля с борта Международной космической станции",
            "Дневной пролёт над планетой",
        )

    private fun bytes(name: String) =
        requireNotNull(javaClass.getResourceAsStream("/fluid/" + name)).use { it.readBytes() }

    private fun snapshot(name: String, captureDialog: Boolean = true): Bitmap {
        compose.waitForIdle()
        val bmp =
            compose.runOnIdle {
                val dialog = ShadowDialog.getLatestDialog()?.takeIf { captureDialog && it.isShowing }
                val root = (dialog?.window ?: compose.activity.window).decorView
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also {
                    val capture =
                        Class.forName("org.robolectric.shadows.HardwareRenderingScreenshot")
                            .getDeclaredMethod(
                                "takeScreenshot",
                                android.view.View::class.java,
                                Bitmap::class.java,
                            )
                    capture.isAccessible = true
                    capture.invoke(null, root, it)
                }
            }
        val target = File("build/reports/fluid/" + name + ".png")
        target.parentFile!!.mkdirs()
        target.outputStream().use { check(bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bmp
    }

    private fun text(id: Int) = compose.onAllNodesWithText(ui(id)).onFirst()

    private fun tab(id: Int) = compose.onNodeWithContentDescription(ui(id)).performClick()

    private fun back() {
        compose.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
            if (dialog != null) dialog.onBackPressed()
            else compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    // Only the test owns this snapshot. The released app reads ManagerModel's agent/cache flow.
    @Suppress("UNCHECKED_CAST")
    private fun loadIssue(
        longHeadline: String? = null, observeServer: Boolean = false,
        articleCount: Int = 105, showEmptyFeedFirst: Boolean = false,
    ): String {
        val now = Instant.now()
        val date = now.minusSeconds(900).toString()
        val server =
            Server(
                java.util.UUID.randomUUID().toString(),
                "VPS · тестовый стенд",
                "example.invalid",
                22,
                "reader",
                false,
                "",
                "",
                "",
                "",
                true,
            )
        val cfg = JSONObject(bytes("config.json").decodeToString())
        val archive =
            (1..9).map { n ->
                JSONObject()
                    .put("id", "past-" + n)
                    .put("created", now.minusSeconds(n * 86400L + 3600).toString())
                    .put("languages", JSONArray(listOf("ru")))
                    .put("total", 12 + n)
            }
        val issue =
            JSONObject()
                .put("id", "current")
                .put("created", date)
                .put("languages", JSONArray(listOf("ru")))
        val clazz = Class.forName("dev.horizonmanager.ArtworkCache")
        val cache = ArtworkCache(compose.activity)
        clazz.getDeclaredField("instance").apply { isAccessible = true }.set(null, cache)
        val memory =
            clazz.getDeclaredField("memory").apply { isAccessible = true }.get(cache)
                as LruCache<String, Cover>
        val pictures = photos.map { requireNotNull(decodeCover(bytes(it + ".jpg"))) }
        val stories =
            (0 until articleCount).map { n ->
                val k = n % 4
                val slug = if (k == 0) "iss040e033340" else photos[k]
                val page = "https://images.nasa.gov/details/" + slug
                val img = "https://images-assets.nasa.gov/image/" + slug + "/" + slug + "~small.jpg"
                JSONObject()
                    .put("id", "n" + n)
                    .put("title", when {
                        n == 1 && longHeadline != null -> longHeadline
                        n < 4 -> headlines[k]
                        else -> headlines[k] + " · " + (n + 1)
                    })
                    .put("url", if (n < 6) page else "")
                    .put("time", date)
                    .put("score", 8)
                    .put("source", if (n < 6) "telegram" else "NASA")
                    .put("telegram_channel", if (n < 6) "nasa_fixture" else "")
                    .put("category", "Космос")
                    .put(
                        "body",
                        "Экипаж Международной космической станции запечатлел рассвет над горизонтом Земли. На снимке видны тонкие слои атмосферы и переход от ночной стороны планеты к дневной.",
                    )
                    .put("body_is_plain", true)
                    .put(
                        "media",
                        if (n < 6) JSONObject().put("kind", "original").put("source", page).put("url", img) else JSONObject(),
                    )
                    .also { if (n < 6) memory.put(artworkKey(server.id, it),
                        pictures[if (n == 1 && longHeadline != null) 0 else k]) }
            }
        val cacheDao = ManagerDb.get(compose.activity).cache()
        runBlocking(Dispatchers.IO) {
            val payload =
                JSONObject()
                    .put("id", "past-1")
                    .put("language", "ru")
                    .put("markdown", "# Архив")
                    .put("items", JSONArray(listOf(stories.first())))
                    .put("total", 1)
            cacheDao.saveDigest(CachedDigest(server.id, "past-1", "ru", 0, payload.toString()))
            cacheDao.saveDigest(CachedDigest(server.id, "current", "ru", 0,
                JSONObject().put("id", "current").put("language", "ru").put("markdown", "# Тестовый выпуск")
                    .put("items", JSONArray(stories)).put("total", stories.size).toString()))
        }
        val loaded = Panel(
            server = server,
            servers = listOf(server),
            ready = true,
            selectedRun = "current",
            syncedDigest = "current",
            language = "ru",
            markdown = "# Тестовый выпуск",
            articles = stories,
            total = stories.size,
            config = cfg.getJSONObject("config"),
            schema = cfg.getJSONObject("schema"),
            schedule = JSONObject().put("spec", JSONObject()
                .put("mode", "calendar").put("hour", 9).put("minute", 0)
                .put("zone", "UTC").put("days", JSONArray((0..6).toList())))
                .put("next", now.plusSeconds(3600).epochSecond),
            archive = listOf(issue) + archive,
            status = JSONObject().put("agent", "0.1.4").put("media_search", false),
            events = listOf(
                JSONObject().put("time", now.toString()).put("code", "SCHEDULE_SKIPPED_ACTIVE")
                    .put("component", "scheduler").put("level", "WARNING"),
                JSONObject().put("time", now.toString()).put("code", "filter")
                    .put("component", "horizon").put("level", "INFO"),
            ),
        )
        val model = ViewModelProvider(compose.activity)[ManagerModel::class.java]
        val field = ManagerModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
        val state = field.get(model) as MutableStateFlow<Panel>
        if (showEmptyFeedFirst) {
            compose.runOnIdle {
                state.value = loaded.copy(selectedRun = "", syncedDigest = "", markdown = "", articles = emptyList(),
                    total = 0, archive = emptyList())
            }
            text(R.string.ui_news_will_appear_here_after_horizon).assertIsDisplayed()
        }
        compose.runOnIdle {
            state.value = loaded
            if (observeServer) ManagerModel::class.java.getDeclaredMethod("observe", String::class.java)
                .apply { isAccessible = true }.invoke(model, server.id)
        }
        compose.waitForIdle()
        return ui(R.string.feed) + " " + shortDigestDate(date)
    }

    @Test
    fun firstDigestHidesAndRestoresBothBarsWithoutChangingTabs() {
        val heading = loadIssue(articleCount = 30, showEmptyFeedFirst = true)
        val header = compose.onNodeWithText(heading)
        val tab = compose.onNodeWithContentDescription(ui(R.string.settings))
        val feed = compose.onNode(hasScrollToIndexAction())
        header.assertIsDisplayed()
        tab.assertIsDisplayed()
        feed.performTouchInput { swipeUp(durationMillis = 420) }
        header.assertDoesNotExist()
        tab.assertDoesNotExist()
        feed.performTouchInput { swipeDown(durationMillis = 420) }
        header.assertIsDisplayed()
        tab.assertIsDisplayed()
    }

    @Test
    fun horizontalSwipesFollowTabOrderAndLeaveTheSystemEdgeAlone() {
        loadIssue(articleCount = 12)
        val screen = compose.onRoot()
        val feed = compose.onNodeWithContentDescription(ui(R.string.feed))
        val settings = compose.onNodeWithContentDescription(ui(R.string.settings))
        val favorites = compose.onNodeWithContentDescription(ui(R.string.favorites_title))
        screen.performTouchInput { swipeLeft(startX = width * .85f, endX = width * .15f) }
        settings.assertIsSelected()
        screen.performTouchInput { swipeLeft(startX = width * .85f, endX = width * .15f) }
        favorites.assertIsSelected()
        screen.performTouchInput { swipeRight(startX = width * .15f, endX = width * .85f) }
        settings.assertIsSelected()
        screen.performTouchInput { swipeRight(startX = width * .15f, endX = width * .85f) }
        feed.assertIsSelected()
        screen.performTouchInput { swipeLeft(startX = right - 5f, endX = centerX) }
        feed.assertIsSelected()
    }

    @Test
    fun bothNavigationBarsUseTheSelectedTheme() {
        val heading = loadIssue(articleCount = 12)
        val title = compose.onNodeWithText(heading).getUnclippedBoundsInRoot()
        val tab = compose.onNodeWithContentDescription(ui(R.string.settings)).getUnclippedBoundsInRoot()
        val graphite = snapshot("17-graphite-navigation")
        compose.runOnUiThread {
            compose.activity.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
                .edit().putString("preset", ThemePreset.Ocean.id).apply()
        }
        val ocean = snapshot("18-ocean-navigation")
        val density = compose.activity.resources.displayMetrics.density
        fun excessBlue(bitmap: Bitmap, x: Float, y: Float): Int {
            val color = bitmap.getPixel((x * density).toInt(), (y * density).toInt())
            return android.graphics.Color.blue(color) - android.graphics.Color.red(color)
        }
        for ((x, y) in listOf(title.left.value + 5f to title.top.value + 2f,
            tab.left.value + 5f to tab.top.value + 12f)) {
            assertTrue("The Ocean glass should be bluer than Graphite",
                excessBlue(ocean, x, y) > excessBlue(graphite, x, y) + 8)
        }
    }

    @Test
    fun feedScrollsBehindTheStatusBarGlass() {
        val heading = loadIssue(articleCount = 30)
        val density = compose.activity.resources.displayMetrics.density
        compose.runOnUiThread {
            val root = compose.activity.findViewById<android.view.View>(android.R.id.content)
            val status = WindowInsetsCompat.Type.statusBars()
            ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                .setInsets(status, Insets.of(0, (24 * density).toInt(), 0, 0))
                .setVisible(status, true)
                .build())
        }
        assertTrue(compose.onNodeWithText(heading).getUnclippedBoundsInRoot().top > 24.dp)
        val beforeScroll = snapshot("19-status-inset")
        compose.onNode(hasScrollToIndexAction()).performTouchInput {
            swipeUp(startY = height * .63f, endY = height * .39f, durationMillis = 500)
        }
        val scrolled = snapshot("20-status-glass")
        val y = (12 * density).toInt()
        val backdropChanged = (scrolled.width / 10 until scrolled.width * 9 / 10 step 8).any { x ->
            beforeScroll.getPixel(x, y) != scrolled.getPixel(x, y)
        }
        assertTrue("Content should remain visible through the status bar glass while scrolling", backdropChanged)
        val center = beforeScroll.width / 2
        val top = android.graphics.Color.red(beforeScroll.getPixel(center, 1))
        val lower = android.graphics.Color.red(beforeScroll.getPixel(center, (23 * density).toInt()))
        assertTrue("The status glass should fade as it approaches its lower edge", lower < top)
    }

    @Test
    fun prefetchedCoverSurvivesMemoryCacheAndNetworkLoss() = runBlocking {
        val article = JSONObject().put("id", java.util.UUID.randomUUID().toString())
        val key = artworkKey("test", article)
        val first = ArtworkCache(compose.activity)
        var calls = 0
        assertNotNull(first.load(key, article, { calls++; bytes("earth.jpg") }, prefetch = true))
        assertNull(first.peek(key))
        val fresh = ArtworkCache(compose.activity)
        article.put("media", JSONObject().put("url", "https://example.invalid/new-cover"))
        assertEquals(key, artworkKey("test", article))
        assertNotNull(fresh.load(key, article, { calls++; null }))
        assertNotNull(fresh.peek(key))
        assertEquals(1, calls)
    }

    @Test
    fun searchAndFiltersStayUnderHeaderAndSettingsExplainParameters() {
        val heading = loadIssue()
        compose.onAllNodesWithText("Telegram").onFirst().assertIsDisplayed()
        val list = compose.onNode(hasScrollToIndexAction())
        val firstStory = compose.onNodeWithText(headlines.first())
        val storyTop = firstStory.getUnclippedBoundsInRoot().top
        tab(R.string.news_search)
        val search = text(R.string.ui_search_downloaded_articles)
        search.assertIsDisplayed()
        assertEquals(storyTop, firstStory.getUnclippedBoundsInRoot().top)
        tab(R.string.editorial_filters_sort)
        text(R.string.ui_sort).assertIsDisplayed()
        assertEquals(storyTop, firstStory.getUnclippedBoundsInRoot().top)
        tab(R.string.news_search)
        val searchPosition = search.getUnclippedBoundsInRoot().top
        list.performScrollToIndex(45)
        search.assertIsDisplayed()
        assertEquals(searchPosition, search.getUnclippedBoundsInRoot().top)
        compose.onNodeWithText(heading).assertIsDisplayed()
        tab(R.string.editorial_filters_sort)
        search.assertDoesNotExist()
        val sort = text(R.string.ui_sort)
        sort.assertIsDisplayed()
        val sortPosition = sort.getUnclippedBoundsInRoot().top
        list.performScrollToIndex(65)
        assertEquals(sortPosition, sort.getUnclippedBoundsInRoot().top)
        snapshot("09-pinned-filters")
        back()
        list.performTouchInput { swipeDown() }
        tab(R.string.settings)
        text(R.string.editorial_ai_processing).performClick()
        compose.onNodeWithContentDescription(ui(R.string.setting_help_for, ui(R.string.digest_output_language))).performClick()
        text(R.string.digest_language_detail).assertIsDisplayed()
        text(R.string.ui_close).performClick()
        text(R.string.digest_language_detail).assertDoesNotExist()
    }

    @Test
    fun scheduleShowsServerRunAndRevealsOnlyRelevantControls() {
        loadIssue()
        tab(R.string.settings)
        text(R.string.mr_news).performClick()
        text(R.string.ui_schedule).performClick()
        compose.onNode(hasText(ui(R.string.schedule_next)) or hasText(ui(R.string.schedule_last_known))).assertExists()
        text(R.string.schedule_every_day).assertExists()
        compose.onNodeWithText("09:00").assertExists()
        text(R.string.ui_interval_from_1_to_168_hours).assertDoesNotExist()
        text(R.string.ui_save_schedule).assertDoesNotExist()
        snapshot("08-schedule")
        text(R.string.schedule_weekends).performScrollTo().performClick()
        text(R.string.ui_save_schedule).assertExists()
        text(R.string.ui_at_regular_intervals).performScrollTo().performClick()
        text(R.string.ui_interval_from_1_to_168_hours).assertExists()
        text(R.string.schedule_time).assertDoesNotExist()
    }

    @Test
    fun settingsExplainDigestSelectionSecretsAndJournal() {
        loadIssue()
        tab(R.string.settings)
        text(R.string.mr_news).performClick()
        text(R.string.settings_digest_rules).performClick()
        text(R.string.digest_basic_title).assertIsDisplayed()
        text(R.string.digest_groups_title).performClick()
        text(R.string.digest_group_add).performScrollTo().performClick()
        text(R.string.digest_categories_detail).performScrollTo().assertIsDisplayed()
        text(R.string.ui_cancel).performScrollTo().performClick()
        text(R.string.digest_basic_title).performScrollTo()
        snapshot("10-digest-rules")
        back()
        back()
        text(R.string.editorial_ai_processing).performClick()
        text(R.string.ui_secrets).performClick()
        text(R.string.secrets_intro).assertIsDisplayed()
        text(R.string.secrets_add).performClick()
        text(R.string.ui_new_value).performScrollTo().assertIsDisplayed()
        text(R.string.ui_cancel).performScrollTo().performClick()
        back()
        text(R.string.ui_processing_profiles).performClick()
        text(R.string.profile_guide_title).performClick()
        text(R.string.profile_guide_score_body).performScrollTo().assertIsDisplayed()
        back()
        back()
        text(R.string.ui_maintenance).performClick()
        text(R.string.journal).performClick()
        val journalList = compose.onNode(hasScrollToIndexAction())
        journalList.performScrollToIndex(2)
        text(R.string.journal_schedule_skipped).performClick()
        text(R.string.journal_technical).assertIsDisplayed()
        compose.onNodeWithText("SCHEDULE_SKIPPED_ACTIVE").assertIsDisplayed()
        snapshot("11-journal-explanation")
    }

    @Test
    fun appIconSettingsSaveAndBackupsOfferTwoTargets() {
        loadIssue()
        tab(R.string.settings)
        text(R.string.mr_interface).performClick()
        text(R.string.hovq_icon).performClick()
        text(R.string.hovq_icon_dark).performClick()
        assertEquals(LauncherAppearance.Dark, LauncherAppearance.current(compose.activity))
        val notice = text(R.string.hovq_settings_saved)
        notice.assertIsDisplayed()
        assertTrue(notice.getUnclippedBoundsInRoot().top < 64.dp)
        compose.mainClock.advanceTimeBy(5_000)
        notice.assertDoesNotExist()
        back()
        back()
        text(R.string.ui_maintenance).performClick()
        text(R.string.settings_transfer).performClick()
        text(R.string.hovq_vps_backup).assertIsDisplayed()
        text(R.string.hovq_device_backup).assertIsDisplayed()
        assertEquals(2, compose.onAllNodesWithText(ui(R.string.hovq_export)).fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodesWithText(ui(R.string.hovq_import)).fetchSemanticsNodes().size)
        snapshot("12-backup-targets")
    }

    @Test
    fun longHeadlineExpandsTileAndKeepsTheSameCoverCrop() {
        val title = "Космический аппарат прислал новые изображения далёкой планеты и подтвердил наличие облаков"
        loadIssue(longHeadline = title)
        val long = compose.onNodeWithText(title, useUnmergedTree = true)
        val shortTile = compose.onNodeWithText(headlines[0], useUnmergedTree = true).onParent()
            .getUnclippedBoundsInRoot()
        val longTile = long.onParent().getUnclippedBoundsInRoot()
        assertTrue(longTile.bottom - longTile.top > shortTile.bottom - shortTile.top)
        val layouts = mutableListOf<TextLayoutResult>()
        long.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        assertFalse(layout.hasVisualOverflow)
        assertEquals(title.length, layout.getLineEnd(layout.lineCount - 1))

        val feed = snapshot("13-headline-and-cover")
        val density = compose.activity.resources.displayMetrics.density
        val sampleY = ((shortTile.top.value + 62) * density).toInt()
        val shortX = ((shortTile.left.value + 66) * density).toInt()
        val longX = ((longTile.left.value + 66) * density).toInt()
        assertEquals(feed.getPixel(shortX, sampleY), feed.getPixel(longX, sampleY))
    }

    @Test
    fun articleMoreIsOnTheRightAndActionsOpenInADismissiblePopup() {
        loadIssue(articleCount = 12)
        compose.onNodeWithText(headlines[0]).performClick()
        val more = text(R.string.article_more).getUnclippedBoundsInRoot()
        val body = compose.onNode(isDialog()).getUnclippedBoundsInRoot()
        assertTrue(more.left > (body.left + body.right) / 2)
        compose.onNodeWithContentDescription(ui(R.string.editorial_article_actions)).performClick()
        compose.onNode(isPopup()).assertExists()
        text(R.string.ui_copy).assertIsDisplayed()
        text(R.string.editorial_share).assertIsDisplayed()
        text(R.string.ui_copy).performClick()
        compose.onNode(isPopup()).assertDoesNotExist()
        compose.onNode(isDialog()).assertExists()
    }

    @Test
    fun realScreensAndBackStack() {
        val heading = loadIssue()
        compose.onNodeWithText(heading).assertExists()
        snapshot("01-feed")
        compose.onNodeWithText(heading).performClick()
        text(R.string.mr_latest).assertExists()
        compose.onNodeWithContentDescription(ui(R.string.settings)).assertIsNotEnabled()
        snapshot("02-bubble")
        back()
        text(R.string.mr_latest).assertDoesNotExist()
        val settingsTab = compose.onNodeWithContentDescription(ui(R.string.settings))
        val settingsBounds = settingsTab.getUnclippedBoundsInRoot()
        val titleBounds = compose.onNodeWithText(heading).getUnclippedBoundsInRoot()
        val openBackground = snapshot("01-before-article")
        compose.onNodeWithText(headlines[0]).performClick()
        compose.onNodeWithContentDescription(ui(R.string.ui_original_source)).assertExists()
        settingsTab.assertIsNotEnabled()
        val blurredBackground = snapshot("03-blurred-background", captureDialog = false)
        val density = compose.activity.resources.displayMetrics.density
        fun contrast(bitmap: Bitmap): Long {
            val left = (titleBounds.left.value * density).toInt()
            val top = (titleBounds.top.value * density).toInt()
            val right = left + (155 * density).toInt()
            val bottom = (titleBounds.bottom.value * density).toInt()
            var difference = 0L
            for (y in top until bottom) for (x in left until right) {
                difference += kotlin.math.abs(
                    android.graphics.Color.red(bitmap.getPixel(x + 1, y)) -
                        android.graphics.Color.red(bitmap.getPixel(x, y)),
                )
            }
            return difference
        }
        assertTrue("The feed header should blur behind the modal",
            contrast(blurredBackground) < contrast(openBackground) * .8f)
        val openArticle = snapshot("03-article")
        // Sample the narrow left margin beside the text in the fixed portrait fixture.
        val x = openArticle.width / 25
        val y = openArticle.height * 51 / 100
        assertEquals("Article art must be clipped to its header", ThemePreset.Graphite.surface.toArgb(),
            openArticle.getPixel(x, y))
        compose.onNode(isDialog()).performTouchInput {
            click(Offset(
                ((settingsBounds.left + settingsBounds.right) / 2).toPx(),
                ((settingsBounds.top + settingsBounds.bottom) / 2).toPx(),
            ))
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        settingsTab.assertIsEnabled()
        compose.onNodeWithText(heading).assertIsDisplayed()

        val scrollable = compose.onNode(hasScrollAction())
        scrollable.performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription(ui(R.string.settings)).assertDoesNotExist()
        compose.onNodeWithContentDescription(ui(R.string.news_search)).assertDoesNotExist()
        scrollable.performTouchInput { swipeDown() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription(ui(R.string.news_search)).assertIsDisplayed()
        val barTop = compose.onNodeWithContentDescription(ui(R.string.settings)).getUnclippedBoundsInRoot().top
        scrollable.performScrollToIndex(104)
        scrollable.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 600f) }
        val lastTile = compose.onNodeWithText(headlines[0] + " · 105", useUnmergedTree = true).onParent()
        val cardBottom = lastTile.getUnclippedBoundsInRoot().bottom
        snapshot("16-glass-navigation-last-card")
        assertTrue("Last card bottom $cardBottom should be above navigation at $barTop", cardBottom <= barTop)
        scrollable.performScrollToIndex(0)
        compose.onNodeWithText(headlines[0]).assertExists()
        tab(R.string.settings)
        snapshot("04-settings")
        text(R.string.editorial_ai_processing).performClick()
        text(R.string.ui_secrets).performClick()
        back()
        text(R.string.ui_processing_profiles).assertExists().performClick()
        text(R.string.mr_default_profile).assertExists()
        snapshot("05-profiles")
        back()
        back()
        text(R.string.ui_maintenance).performClick()
        text(R.string.ui_secrets).performClick()
        val backButton = compose.onNodeWithContentDescription(ui(R.string.ui_back))
        val secretsTitle = text(R.string.ui_secrets)
        assertTrue(backButton.getUnclippedBoundsInRoot().right <= secretsTitle.getUnclippedBoundsInRoot().left)
        backButton.performClick()
        text(R.string.journal).assertExists()
        back()
        text(R.string.mr_server).performClick()
        text(R.string.ui_servers).performClick()
        val serverName = "VPS · тестовый стенд"
        compose.onNodeWithContentDescription(ui(R.string.server_remove_named, serverName)).performClick()
        compose.onNodeWithText(ui(R.string.server_remove_message, serverName)).assertIsDisplayed()
        text(R.string.ui_cancel).performClick()
        compose.onNodeWithText(serverName).assertIsDisplayed()
        back()
        text(R.string.control).performClick()
        text(R.string.ui_check_status).assertExists()
        snapshot("07-server")
        back()
        back()
        tab(R.string.settings)
        text(R.string.archive_digests).performClick()
        snapshot("06-archive")
        text(R.string.editorial_archive_search).assertExists()
        compose.onNodeWithText(ui(R.string.mr_offline_copy), substring = true).performClick()
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText(headlines[0]).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(headlines[0]).performClick()
        compose.onNodeWithContentDescription(ui(R.string.ui_original_source)).assertExists()
        back()
        back()
        text(R.string.editorial_archive_search).assertExists()
    }

    @Test
    fun glassNavigationCountsCurrentNewsAndKeepsEqualTabsDuringSwitches() {
        loadIssue(observeServer = true, articleCount = 12)
        val feed = compose.onNodeWithContentDescription(ui(R.string.feed))
        val settings = compose.onNodeWithContentDescription(ui(R.string.settings))
        val favorites = compose.onNodeWithContentDescription(ui(R.string.favorites_title))
        fun waitForCount(count: Int) {
            compose.waitUntil(5_000) {
                val node = compose.onAllNodesWithContentDescription(ui(R.string.feed)).fetchSemanticsNodes().singleOrNull()
                node?.config?.getOrElse(SemanticsProperties.StateDescription) { "" } == ui(R.string.nav_unread, count)
            }
        }
        waitForCount(12)
        feed.assertIsSelected()
        val bounds = listOf(feed, settings, favorites).map { it.getUnclippedBoundsInRoot() }
        val widths = bounds.map { (it.right - it.left).value }
        assertTrue(widths.max() - widths.min() <= 1f)
        assertTrue(bounds.all { it.top == bounds.first().top && it.bottom == bounds.first().bottom })
        assertTrue("The expanded bar should cover about three quarters of the available width", widths.sum() in 265f..291f)
        assertTrue(bounds.all { it.bottom - it.top in 59.dp..62.dp })
        snapshot("14-glass-navigation")

        compose.onNodeWithText(headlines[0]).performClick()
        waitForCount(11)
        back()
        settings.performClick()
        settings.assertIsSelected()
        waitForCount(11)
        favorites.performClick()
        favorites.assertIsSelected()
        waitForCount(11)

        val panel = compose.runOnIdle { ViewModelProvider(compose.activity)[ManagerModel::class.java].state.value }
        val cache = ManagerDb.get(compose.activity).cache()
        runBlocking(Dispatchers.IO) {
            val page = JSONObject().put("items", JSONArray(listOf(panel.articles.first(),
                JSONObject().put("id", "new-news").put("title", "Новость после синхронизации"))))
            cache.saveDigest(CachedDigest(panel.server!!.id, "current", "ru", 12, page.toString()))
        }
        waitForCount(12)
        feed.performClick()
        feed.assertIsSelected()
        waitForCount(12)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w320dp-h720dp-xhdpi")
    fun narrowNavigationFitsLargeFontsAndHandlesFastTaps() {
        val originalScale = org.robolectric.RuntimeEnvironment.getFontScale()
        try {
            org.robolectric.RuntimeEnvironment.setFontScale(1.8f)
            compose.activityRule.scenario.recreate()
            loadIssue(articleCount = 12)
            val feed = compose.onNodeWithContentDescription(ui(R.string.feed))
            val settings = compose.onNodeWithContentDescription(ui(R.string.settings))
            val favorites = compose.onNodeWithContentDescription(ui(R.string.favorites_title))
            compose.mainClock.autoAdvance = false
            try {
                for (destination in listOf(settings, favorites, settings, feed)) {
                    destination.performClick()
                    compose.mainClock.advanceTimeBy(48)
                }
            } finally {
                compose.mainClock.autoAdvance = true
            }
            feed.assertIsSelected()
            favorites.assertIsDisplayed()
            val tabs = listOf(feed, settings, favorites).map { it.getUnclippedBoundsInRoot() }
            assertTrue(tabs.first().left.value >= 12f && tabs.last().right.value <= 308f)
            assertTrue(tabs.all { it.bottom - it.top >= 48.dp })
            snapshot("15-glass-navigation-large-type")
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(originalScale)
        }
    }

    @Test
    fun browserBackRestoresTheSameOpenArticle() {
        loadIssue()
        compose.onNodeWithText(headlines[0]).performClick()
        compose.onNodeWithContentDescription(ui(R.string.ui_original_source)).performClick()
        compose.onNodeWithText("https://images.nasa.gov/details/iss040e033340").assertExists()
        back()
        compose.onNodeWithText(headlines[0]).assertIsDisplayed()
        compose.onNodeWithContentDescription(ui(R.string.ui_original_source)).assertIsDisplayed()
    }

    @Test
    fun favoriteFromArchiveSurvivesDeletionAndCanBeUnstarredWhileOpen() {
        loadIssue(observeServer = true)
        tab(R.string.settings)
        text(R.string.archive_digests).performClick()
        compose.onNodeWithText(ui(R.string.mr_offline_copy), substring = true).performClick()
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText(headlines[0]).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(headlines[0]).performClick()
        compose.onNodeWithContentDescription(ui(R.string.add_to_favorites)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(ui(R.string.remove_from_favorites)).fetchSemanticsNodes().isNotEmpty()
        }
        back()
        back()
        val cachedIssue = compose.onNodeWithText(ui(R.string.mr_offline_copy), substring = true)
        compose.onAllNodesWithContentDescription(ui(R.string.archive_issue_actions)).onFirst().performClick()
        text(R.string.archive_remove_device).performClick()
        compose.onAllNodesWithText(ui(R.string.archive_remove_device)).onLast().performClick()
        compose.waitUntil(5_000) { cachedIssue.isNotDisplayed() }
        back()
        tab(R.string.favorites_title)
        compose.onNodeWithText(headlines[0]).performClick()
        compose.onNodeWithText("Экипаж Международной космической станции", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(ui(R.string.remove_from_favorites)).performClick()
        compose.onNodeWithText(headlines[0]).assertIsDisplayed()
        back()
        text(R.string.favorites_empty).assertIsDisplayed()
        compose.onNodeWithText(headlines[0]).assertDoesNotExist()
    }
}
