package dev.horizonmanager

import org.json.JSONObject
import java.net.URI
import java.util.Locale

private val telegramPostPath = Regex("/(?:(?:s/)?[A-Za-z][A-Za-z0-9_]*|c/[0-9]+)/(?:[0-9]+/)?[0-9]+/?")
private val redditPostPath = Regex("/(?:r/[^/]+/|user/[^/]+/)?comments/[A-Za-z0-9]+(?:/.*)?")
private val hackerNewsId = Regex("(?:^|&)id=[0-9]+(?:&|$)")

internal fun originalPublicationUrl(article: JSONObject): String? {
    val source = articleField(article, "source").lowercase(Locale.ROOT)
    val legacy = articleField(article, "url")
    val candidates = when (source) {
        "telegram" -> listOf(
            articleField(article, "source_url"), articleField(article, "telegram_url"), legacy,
            article.optJSONObject("media")?.let { articleField(it, "source") }.orEmpty(),
        )
        else -> listOf(articleField(article, "source_url"), legacy)
    }
    return candidates.firstOrNull { url ->
        val uri = runCatching { URI(url) }.getOrNull() ?: return@firstOrNull false
        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
        if (host.isNullOrEmpty() || !isWeb(url) || uri.rawUserInfo != null) return@firstOrNull false
        val path = uri.path.orEmpty()
        when (source) {
            "telegram" -> host in setOf("t.me", "telegram.me", "telegram.dog") && telegramPostPath.matches(path)
            "reddit" -> (host == "reddit.com" || host == "old.reddit.com" || host == "new.reddit.com") &&
                redditPostPath.matches(path) || host == "redd.it" && path.matches(Regex("/[A-Za-z0-9]+/?"))
            "hackernews" -> host == "news.ycombinator.com" && path == "/item" &&
                hackerNewsId.containsMatchIn(uri.rawQuery.orEmpty())
            else -> true
        }
    }
}
