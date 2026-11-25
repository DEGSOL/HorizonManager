package dev.horizonmanager

import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class AppRelease(val tag: String, val version: String) {
    val pageUrl get() = "https://github.com/DEGSOL/HorizonManager/releases/tag/$tag"

    companion object {
        private val stable = Regex("""v?(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})""")

        fun newerRelease(json: JSONObject, installed: String): AppRelease? {
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val tag = json.optString("tag_name")
            val remote = stable.matchEntire(tag)?.groupValues?.drop(1) ?: return null
            val local = stable.matchEntire(installed)?.groupValues?.drop(1) ?: return null
            val difference = remote.zip(local).firstOrNull { (left, right) -> left != right } ?: return null
            if (difference.first.toInt() < difference.second.toInt()) return null
            val version = remote.joinToString(".")
            val assets = json.optJSONArray("assets") ?: return null
            val hasApk = (0 until assets.length()).any { index ->
                val asset = assets.optJSONObject(index)
                asset?.optString("name") == "Hovq_${version}.apk" && asset.optString("state") == "uploaded"
            }
            return AppRelease(tag, version).takeIf { hasApk }
        }
    }
}

internal class AppUpdates(private val prefs: SharedPreferences) {
    private val mutable = MutableStateFlow<AppRelease?>(null)
    val available = mutable.asStateFlow()
    private val lock = Mutex()
    private var checkedRelease: AppRelease? = null
    private var nextCheckAt = 0L

    suspend fun refresh() = lock.withLock {
        val now = SystemClock.elapsedRealtime()
        if (now >= nextCheckAt) {
            nextCheckAt = now + RETRY_DELAY
            try {
                checkedRelease = withContext(Dispatchers.IO) {
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("GitHub release lookup failed")
                        AppRelease.newerRelease(JSONObject(response.peekBody(256 * 1024).string()), BuildConfig.VERSION_NAME)
                    }
                }
                nextCheckAt = now + CHECK_DELAY
            } catch (_: IOException) {
                // Keep the known release if GitHub is temporarily unreachable.
            } catch (_: JSONException) {
                // GitHub did not return a release object. Try again on a later resume.
            }
        }
        mutable.value = checkedRelease?.takeUnless {
            prefs.getString("postponed_version", null) == it.version &&
                prefs.getLong("postponed_until", 0L) > System.currentTimeMillis()
        }
    }

    fun postpone(release: AppRelease) {
        prefs.edit().putString("postponed_version", release.version)
            .putLong("postponed_until", System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1)).apply()
        if (mutable.value == release) mutable.value = null
    }

    companion object {
        private val CHECK_DELAY = TimeUnit.HOURS.toMillis(6)
        private val RETRY_DELAY = TimeUnit.MINUTES.toMillis(15)
        private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).followRedirects(false).build()
        private val request = Request.Builder()
            .url("https://api.github.com/repos/DEGSOL/HorizonManager/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
    }
}
