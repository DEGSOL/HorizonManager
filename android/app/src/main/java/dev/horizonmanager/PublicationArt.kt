package dev.horizonmanager

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

internal fun publicPhotoAddress(address: InetAddress): Boolean {
    if (
        address.isAnyLocalAddress ||
            address.isLinkLocalAddress ||
            address.isLoopbackAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
    )
        return false
    val b = address.address.map { it.toInt() and 255 }
    if (b.size == 4)
        return b[0] in 1..223 &&
            !(b[0] == 100 && b[1] in 64..127) &&
            !(b[0] == 192 && (b[1] == 0 || b[1] == 88)) &&
            !(b[0] == 198 && b[1] in listOf(18, 19, 51)) &&
            !(b[0] == 203 && b[1] == 0)
    return b[0] in 32..63 &&
        !(b[0] == 32 && (b[1] == 2 || b[1] == 1 && (b[2] == 0 || b[2] == 13 && b[3] == 184)))
}

internal data class Cover(val image: ImageBitmap, val soft: ImageBitmap, val brightness: Float)

private class ArtworkCache(context: Context) {
    private val http =
        OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY)
            .dns(
                object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> =
                        Dns.SYSTEM.lookup(hostname).also { addresses ->
                            if (addresses.isEmpty() || addresses.any { !publicPhotoAddress(it) })
                                throw UnknownHostException()
                        }
                }
            )
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.isSuccessful && response.header("Cache-Control") == null)
                    response.newBuilder().header("Cache-Control", "private, max-age=259200").build()
                else response
            }
            .cache(Cache(File(context.cacheDir, "mrizon-media-http"), 64L * 1024 * 1024))
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    private val lookup = context.getSharedPreferences("mrizon-media-links", Context.MODE_PRIVATE)
    private val memory =
        object : LruCache<String, Cover>(20 * 1024) {
            override fun sizeOf(key: String, value: Cover) =
                max(1, value.image.width * value.image.height / 256)
        }
    private val slots = Semaphore(3)

    private data class MediaResponse(val bytes: ByteArray, val url: String)

    private fun bytes(url: String, html: Boolean): MediaResponse? {
        var u = url.toHttpUrlOrNull() ?: return null
        repeat(4) {
            if (!u.isHttps || u.port != 443 || u.username.isNotEmpty() || u.password.isNotEmpty())
                return null
            // OkHttp can bypass Dns for literal addresses.
            if (u.host.contains(':') || u.host.matches(Regex("[0-9.]+"))) {
                if (!publicPhotoAddress(InetAddress.getByName(u.host))) return null
            }
            val request = Request.Builder().url(u).header("User-Agent", "MRizon/0.3").build()
            val response =
                try {
                    http.newCall(request).execute()
                } catch (failure: java.io.IOException) {
                    http
                        .newCall(
                            request.newBuilder().cacheControl(CacheControl.FORCE_CACHE).build()
                        )
                        .execute()
                }
            response.use { res ->
                if (res.isRedirect) {
                    u = u.resolve(res.header("Location") ?: return null) ?: return null
                } else {
                    val body = res.body ?: return null
                    val type = body.contentType()?.let { it.type + "/" + it.subtype }
                    val limit = if (html) 512_000L else 5_000_000L
                    if (
                        !res.isSuccessful ||
                            body.contentLength() > limit ||
                            (if (html) type != "text/html"
                            else type !in setOf("image/png", "image/webp", "image/jpeg"))
                    )
                        return null
                    val input = body.source()
                    if (input.request(limit + 1)) return null
                    return MediaResponse(input.readByteArray(), u.toString())
                }
            }
        }
        return null
    }

    private fun originals(page: String): List<String> {
        val u = page.toHttpUrlOrNull() ?: return emptyList()
        val telegram = u.host in setOf("t.me", "telegram.me", "telegram.dog")
        val html = bytes(
            if (telegram) u.newBuilder().setQueryParameter("embed", "1").fragment(null).build().toString()
            else page,
            true,
        ) ?: return emptyList()
        val doc = Jsoup.parse(html.bytes.decodeToString(), html.url)
        if (telegram) {
            val post = u.encodedPath.trim('/').removePrefix("s/")
            val node = doc.select("[data-post]").firstOrNull { it.attr("data-post").equals(post, ignoreCase = true) }
            val pattern = Regex("""url\(\s*['"]?([^)'"]+)""")
            return node?.select(".tgme_widget_message_photo_wrap, .tgme_widget_message_video_thumb, video[poster]").orEmpty()
                .mapNotNull {
                    it.absUrl("poster").ifBlank { it.absUrl("data-src") }.ifBlank { it.absUrl("src") }
                        .ifBlank {
                            val path = pattern.find(it.attr("style"))?.groupValues?.get(1)?.trim().orEmpty()
                            if (path.isEmpty()) "" else html.url.toHttpUrlOrNull()?.resolve(path)?.toString().orEmpty()
                        }
                        .takeIf(String::isNotBlank)
                }
        }
        return listOf("meta[property=og:image:secure_url]", "meta[property=og:image]",
            "meta[name=twitter:image]", "article img[src]").flatMap { selector ->
            doc.select(selector).map { it.absUrl(if (it.hasAttr("content")) "content" else "src") }
        }.filter(String::isNotEmpty)
    }

    private fun loadCover(url: String): Cover? =
        try {
            if (url.isEmpty()) null else bytes(url, false)?.bytes?.let(::decodeCover)
        } catch (_: java.io.IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun fallback(page: String, failed: String): Cover? {
        if (page.isBlank()) return null
        val key = contextHash(page)
        val cached = lookup.getString(key, null)
        if (cached != null && System.currentTimeMillis() < lookup.getLong("$key.until", 0)) {
            if (cached.isEmpty()) return null
            if (cached != failed) loadCover(cached)?.let { return it }
        }
        val candidates = originals(page).asSequence().filter { it != cached && it != failed }.distinct().take(4)
        var resolved = ""
        val cover = candidates.firstNotNullOfOrNull { url ->
            loadCover(url)?.also { resolved = url }
        }
        val ttl = if (cover == null) 1_800_000L else 259_200_000L
        lookup.edit().putString(key, resolved)
            .putLong("$key.until", System.currentTimeMillis() + ttl)
            .apply()
        return cover
    }

    suspend fun load(article: JSONObject, relay: (suspend () -> ByteArray?)?): Cover? =
        withContext(Dispatchers.IO) {
            val page = articleField(article, "url")
            val media = article.optJSONObject("media")
            val provided = media?.optString("url").orEmpty()
            val id = page + "|" + provided.ifBlank { article.optString("id") }
            memory.get(id)?.let {
                return@withContext it
            }
            slots.withPermit {
                memory.get(id)?.let {
                    return@withPermit it
                }
                try {
                    val telegram = articleField(article, "telegram_url")
                    val cover = relay?.invoke()?.let(::decodeCover)
                        ?: loadCover(provided)
                        ?: fallback(telegram, provided)
                        ?: if (page != telegram) fallback(page, provided) else null
                    cover?.also { memory.put(id, it) }
                } catch (_: java.io.IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }

    companion object {
        @Volatile private var instance: ArtworkCache? = null

        fun get(context: Context) =
            instance
                ?: synchronized(this) {
                    instance ?: ArtworkCache(context.applicationContext).also { instance = it }
                }
    }
}

internal fun decodeCover(bytes: ByteArray): Cover? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (
        options.outMimeType !in setOf("image/jpeg", "image/png", "image/webp") ||
            options.outWidth !in 1..16000 ||
            options.outHeight !in 1..16000 ||
            options.outWidth.toLong() * options.outHeight > 42_000_000
    )
        return null
    options.inJustDecodeBounds = false
    while (max(options.outWidth, options.outHeight) / max(1, options.inSampleSize) > 960) options
        .inSampleSize = max(1, options.inSampleSize) * 2
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    val factor = 38f / max(source.width, source.height)
    val small =
        Bitmap.createScaledBitmap(
            source,
            max(1, (source.width * factor).roundToInt()),
            max(1, (source.height * factor).roundToInt()),
            true,
        )
    val w = small.width
    val h = small.height
    val input = IntArray(w * h)
    val output = IntArray(w * h)
    small.getPixels(input, 0, w, 0, 0, w, h)
    var light = 0.0
    for (y in 0 until h) for (x in 0 until w) {
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (sy in max(0, y - 2)..minOf(h - 1, y + 2)) for (sx in
            max(0, x - 2)..minOf(w - 1, x + 2)) {
            val c = input[sy * w + sx]
            r += (c ushr 16) and 255
            g += (c ushr 8) and 255
            b += c and 255
            n++
        }
        output[y * w + x] = android.graphics.Color.rgb(r / n, g / n, b / n)
        light += (.213 * r + .715 * g + .072 * b) / n / 255
    }
    val soft = Bitmap.createBitmap(output, w, h, Bitmap.Config.ARGB_8888)
    return Cover(source.asImageBitmap(), soft.asImageBitmap(), (light / (w * h)).toFloat())
}

@Composable
internal fun artwork(article: JSONObject, relay: (suspend () -> ByteArray?)? = null): Cover? {
    val context = LocalContext.current
    val key = article.optString("id") + article.optString("url") + article.optString("media") +
        article.optString("telegram_url")
    return produceState<Cover?>(null, key, relay != null) {
        value = ArtworkCache.get(context).load(article, relay)
    }.value
}

private fun DrawScope.crop(image: ImageBitmap) {
    val scale = max(size.width / image.width, size.height / image.height)
    val w = max(1, (image.width * scale).roundToInt())
    val h = max(1, (image.height * scale).roundToInt())
    drawImage(
        image,
        dstOffset =
            IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
        dstSize = IntSize(w, h),
        filterQuality = FilterQuality.Medium,
    )
}

@Composable
internal fun CoverSurface(cover: Cover?, seed: String, textHeight: Float, modifier: Modifier) {
    val palette = LocalHorizonPalette.current
    Canvas(modifier) {
        val tone =
            listOf(
                    Color(0xff835941),
                    Color(0xff384e66),
                    Color(0xff716579),
                    Color(0xff4d6968),
                    Color(0xff746745),
                )
                .get(Math.floorMod(seed.hashCode(), 5))
        if (cover != null) crop(cover.image)
        else {
            drawRect(
                Brush.linearGradient(listOf(tone, palette.surface, lerp(tone, palette.tint, .3f)))
            )
            val center =
                Offset(
                    size.width * (.20f + Math.floorMod(seed.hashCode(), 65) / 100f),
                    size.height * .18f,
                )
            drawCircle(
                Brush.radialGradient(
                    listOf(tone.copy(alpha = .15f), tone.copy(alpha = .6f), Color.Transparent),
                    center = center,
                    radius = size.width * .75f,
                ),
                size.width * .75f,
                center,
            )
        }
        val start = (size.height - textHeight - 24.dp.toPx()).coerceAtLeast(0f)
        if (cover != null) {
            val paint = Paint()
            drawContext.canvas.saveLayer(
                androidx.compose.ui.geometry.Rect(0f, start, size.width, size.height),
                paint,
            )
            clipRect(top = start) {
                crop(cover.soft)
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black),
                        startY = start,
                        endY = start + 26.dp.toPx(),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            drawContext.canvas.restore()
        }
        drawRect(
            Brush.verticalGradient(
                listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = .35f + (cover?.brightness ?: .4f) * .42f),
                    Color.Black.copy(alpha = .72f),
                ),
                startY = start,
                endY = size.height,
            )
        )
    }
}
