package dev.horizonmanager

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

    private fun snapshot(name: String) {
        compose.waitForIdle()
        val bmp =
            compose.runOnIdle {
                val root = compose.activity.window.decorView
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
    }

    private fun text(id: Int) = compose.onAllNodesWithText(ui(id)).onFirst()

    private fun tab(id: Int) = compose.onNodeWithContentDescription(ui(id)).performClick()

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    // Only the test owns this snapshot. The released app reads ManagerModel's agent/cache flow.
    @Suppress("UNCHECKED_CAST")
    private fun loadIssue(): String {
        val now = Instant.now()
        val date = now.minusSeconds(900).toString()
        val server =
            Server(
                "visual",
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
            (0 until 105).map { n ->
                val k = n % 4
                val slug = if (k == 0) "iss040e033340" else photos[k]
                val page = "https://images.nasa.gov/details/" + slug
                val img = "https://images-assets.nasa.gov/image/" + slug + "/" + slug + "~small.jpg"
                JSONObject()
                    .put("id", "n" + n)
                    .put("title", if (n < 4) headlines[k] else headlines[k] + " · " + (n + 1))
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
                    .also { if (n < 6) memory.put(artworkKey(server.id, it), pictures[k]) }
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
        }
        compose.runOnIdle {
            val model = ViewModelProvider(compose.activity)[ManagerModel::class.java]
            val field =
                ManagerModel::class.java.getDeclaredField("mutable").apply { isAccessible = true }
            (field.get(model) as MutableStateFlow<Panel>).value =
                Panel(
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
                        .put("zone", "Asia/Yekaterinburg").put("days", JSONArray((0..6).toList())))
                        .put("next", now.plusSeconds(3600).epochSecond),
                    archive = listOf(issue) + archive,
                    status = JSONObject().put("agent", "0.1.4").put("media_search", false),
                )
        }
        compose.waitForIdle()
        return ui(R.string.feed) + " " + shortDigestDate(date)
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
        list.performScrollToIndex(25)
        tab(R.string.news_search)
        val search = text(R.string.ui_search_downloaded_articles)
        search.assertIsDisplayed()
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
        compose.onNodeWithText(headlines[0]).performClick()
        compose.onNodeWithContentDescription(ui(R.string.ui_original_source)).assertExists()
        compose.onNodeWithContentDescription(ui(R.string.settings)).assertIsNotEnabled()
        snapshot("03-article")
        back()
        val scrollable = compose.onNode(hasScrollAction())
        scrollable.performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription(ui(R.string.settings)).assertDoesNotExist()
        scrollable.performTouchInput { swipeDown() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription(ui(R.string.settings)).assertExists()
        scrollable.performScrollToIndex(104)
        compose.onNodeWithText(headlines[0] + " · 105").assertExists()
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
        text(R.string.ui_back).performClick()
        text(R.string.journal).assertExists()
        back()
        text(R.string.mr_server).performClick()
        text(R.string.control).performClick()
        text(R.string.ui_check_status).assertExists()
        snapshot("07-server")
        back()
        back()
        tab(R.string.archive)
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
}
