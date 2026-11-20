package dev.horizonmanager

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EditorialBehaviorTest {
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
    }
}
