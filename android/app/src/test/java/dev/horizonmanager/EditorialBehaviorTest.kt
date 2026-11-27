package dev.horizonmanager

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EditorialBehaviorTest {
    @Test
    fun editingSshFieldsKeepsTheSavedProfileIdentity() {
        val saved = Server("same-id", "Home", "old.example", 22, "user", false,
            "fixture-password", "", "SHA256:expected", "fixture-token", installed = true,
            https = "https://agent.example", background = true)
        val draft = ServerDraft(saved).copy(name = " Home VPS ", host = "[2001:db8::123]", port = " 2222 ")
        val result = requireNotNull(draft.update(saved))
        assertEquals(saved.copy(name = "Home VPS", host = "2001:db8::123", port = 2222), result)
        assertFalse(result.sameSshAddress(saved))
        assertTrue(result.sameSshCredentials(saved))
        assertNull(draft.copy(port = "65536").update(saved))
        assertNull(draft.copy(credential = "").update(saved))
    }

    @Test
    fun titleShadowIsAlwaysTheOppositeColorOfItsText() {
        for (luminance in listOf(.02f, .178f, .179f, .80f)) {
            val ink = CoverInk.at(luminance, 4f, 1f)
            assertEquals(1f - ink.color.red, ink.shadow.color.red, .001f)
        }
    }

    @Test
    fun pinchingKeepsTheFocalPointAndDraggingClampsToThePicture() {
        val frame = androidx.compose.ui.unit.IntSize(400, 800)
        val image = androidx.compose.ui.unit.IntSize(400, 400)
        val focus = Offset(270f, 400f)
        val zoom = PhotoViewport().transform(focus, Offset.Zero, 3f, frame, image)
        assertEquals(PhotoViewport(3f, Offset(-140f, 0f)), zoom)
        val drag = zoom.transform(focus, Offset(900f, -900f), 1f, frame, image)
        assertEquals(PhotoViewport(3f, Offset(400f, -200f)), drag)
        assertEquals(PhotoViewport(), drag.transform(focus, Offset.Zero, .1f, frame, image))
    }

    @Test
    fun identifiesOriginalPostsWithoutFollowingEmbeddedLinks() {
        val article = JSONObject().put("source", "telegram").put("url", "https://example.org/news")
        assertNull(originalPublicationUrl(article))
        article.put("telegram_url", "https://t.me/s/realChannel/42?single")
        assertEquals("https://t.me/s/realChannel/42?single", originalPublicationUrl(article))
        article.put("source_url", "https://t.me/c/123/45")
        assertEquals("https://t.me/c/123/45", originalPublicationUrl(article))
        article.put("source", "reddit").put("source_url", "https://reddit.com/r/android/comments/abc123/post/")
        assertEquals("https://reddit.com/r/android/comments/abc123/post/", originalPublicationUrl(article))
        article.put("source", "hackernews").put("source_url", "https://news.ycombinator.com/item?id=42")
        assertEquals("https://news.ycombinator.com/item?id=42", originalPublicationUrl(article))
        article.put("source_url", "https://news.ycombinator.com@bad.invalid/item?id=42")
        assertNull(originalPublicationUrl(article))
        article.put("source", "rss").remove("source_url")
        assertEquals("https://example.org/news", originalPublicationUrl(article))
    }

    @Test
    fun coverActionsUseLocalContrastAfterTheCenterCrop() {
        val lightAndDark = IntArray(40 * 20) { index ->
            if (index % 40 < 20) 0xfffafafa.toInt() else 0xff151515.toInt()
        }
        val artwork = CoverLuminance(40, 20, lightAndDark)
        val frame = Size(100f, 100f)
        assertTrue(artwork.prefersBlack(frame, Offset(20f, 50f), 24f))
        assertFalse(artwork.prefersBlack(frame, Offset(80f, 50f), 24f))
    }

    @Test
    fun unreadBadgeHidesZeroAndCapsItsVisibleNumber() {
        assertEquals(listOf(null, "1", "9", "12", "99", "99+", "99+"),
            listOf(0, 1, 9, 12, 99, 100, 115).map(::unreadBadgeText))
    }

    @Test
    fun savesNewsTextSeparatelyWhenOtherProfileFieldsAreUnchanged() {
        val originals = JSONArray("""[{"definition":{"id":"tech-news","name":"News",
            "content":{"analysis_max_chars":6000,"enrichment_max_chars":8000,"sampling":"prefix"}},
            "analysis_prompt":"Original scoring policy","summary_mode":"news","summary_instruction":"Keep details"}]""")
        val edited = JSONArray(originals.toString())
        val profile = edited.getJSONObject(0)
        val definition = profile.getJSONObject("definition")
        val content = definition.getJSONObject("content")
        profile.put("summary_mode", "original")
        content.put("analysis_max_chars", 7500).put("sampling", "head-middle-tail")
        val changes = requireNotNull(changedProfileNewsText(originals, edited, "Default"))
        assertEquals(1, changes.length())
        val options = changes.getJSONObject("tech-news")
        assertEquals("original", options.getString("summary_mode"))
        assertEquals("Keep details", options.getString("summary_instruction"))
        assertEquals(7500, options.getJSONObject("content").getInt("analysis_max_chars"))
        assertEquals("head-middle-tail", options.getJSONObject("content").getString("sampling"))
        assertFalse(options.has("analysis_prompt"))
        definition.put("name", "New name")
        assertNull(changedProfileNewsText(originals, edited, "Default"))
    }

    @Test
    fun profileRenamePreservesLinkedRoutesAndUnrelatedText() {
        val raw=JSONObject("""{"sources":{"rss":[{"profile":["old","other"],"name":"old"}]},
            "processing":{"default_profile":"old","profile_settings":{"old":{"weight":2}}},
            "digest":{"group_by":"profile","profile_order":["old"],"category_groups":{"one":{"categories":["old"]}}}}""")
        val actual=renameProfileReferences(raw,"old","custom")
        assertEquals("custom",actual.getJSONObject("processing").getString("default_profile"))
        assertTrue(actual.getJSONObject("processing").getJSONObject("profile_settings").has("custom"))
        val source=actual.getJSONObject("sources").getJSONArray("rss").getJSONObject(0)
        assertEquals("custom",source.getJSONArray("profile").getString(0))
        assertEquals("old",source.getString("name"))
        assertEquals("old",raw.getJSONObject("processing").getString("default_profile"))
    }

    @Test
    fun normalizesPublicTelegramHandlesAndRejectsInviteLinks() {
        for (input in listOf("  @myChannel ", "myChannel", "https://t.me/myChannel/")) {
            assertEquals("myChannel", normalizeTelegramChannel(input))
        }
        for (input in listOf("", "https://t.me/+secret", "https://t.me/joinchat/key", "https://evil.test/myChannel",
            "https://user:secret@t.me/myChannel", "https://t.me/myChannel/42", "my channel")) {
            assertNull(input, normalizeTelegramChannel(input))
        }
    }

    @Test
    fun telegramCardsUsePublisherEvenWhenTheArticleLinksOut() {
        val article = JSONObject().put("source", "telegram").put("url", "https://example.org/news")
        assertEquals("", telegramPublisher(article))
        article.put("telegram_url", "https://t.me/s/realChannel/42?single")
        assertEquals("@realChannel", telegramPublisher(article))
        article.put("telegram_channel", "explicitChannel")
        assertEquals("@explicitChannel", telegramPublisher(article))
        article.remove("telegram_channel")
        article.put("telegram_url", "https://t.me/c/123/45")
        assertEquals("", telegramPublisher(article))
    }

    @Test
    fun movingChannelPreservesOtherEntries() {
        val channels = listOf("first", "second", "third")
        assertEquals(listOf("third", "first", "second"), moveSource(channels, 2, 0))
        assertEquals(listOf("second", "third", "first"), moveSource(channels, 0, 2))
        assertEquals(channels, moveSource(channels, 0, 3))
    }

    @Test
    fun sortsBothScoreDirectionsAndKeepsUnknownValuesLast() {
        val missing = JSONObject().put("id", "missing")
        val low = JSONObject().put("score", 2).put("time", "2026-11-19T18:00:00+05:00")
        val high = JSONObject().put("score", 9).put("time", "2026-11-19T15:00:00Z")
        val entries = listOf(missing, low, high)
        assertEquals(listOf(high, low, missing), orderArticles(entries, NewsOrder.Score))
        assertEquals(listOf(low, high, missing), orderArticles(entries, NewsOrder.ScoreLow))
        assertEquals(listOf(high, low, missing), orderArticles(entries, NewsOrder.Newest))
        assertEquals(listOf(low, high, missing), orderArticles(entries, NewsOrder.Oldest))
    }

    @Test
    fun diagnosticCopyOmitsRawCodesAndStringsAndIncludesCriticalErrors() {
        val row = JSONObject().put("id", 8).put("code", "SSH_AUTH · SECRET_PASSWORD")
            .put("detail", "Bearer PRIVATE_TOKEN").put("level", "CRITICAL")
            .put("component", "android").put("api_key", "APISECRET")
        val sanitized = diagnosticEvent(row)
        assertEquals("SSH_AUTH", sanitized.getString("code"))
        assertFalse(sanitized.has("detail"))
        assertFalse(sanitized.toString().contains("SECRET"))
        assertTrue(matchesEventLevel("ERROR", "CRITICAL"))
        assertFalse(matchesEventLevel("WARNING", "CRITICAL"))

        val detail = "tcp/dns · Problem @ Ssh.connect:178 ← SocketTimeoutException"
        row.put("code", "SSH_TIMEOUT").put("detail", detail)
        assertEquals(detail, diagnosticEvent(row).getString("detail"))
    }
}
