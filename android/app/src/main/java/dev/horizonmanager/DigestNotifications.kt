package dev.horizonmanager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

private val digestId = Regex("(?:[a-f0-9]{32}|run-[0-9]{8}T[0-9]{6}Z-[a-f0-9]{8})")
internal fun validDigestMarker(value: String): Boolean = value.isEmpty() || digestId.matches(value)

internal object DigestNotifications {
    private fun prefs(app: Context) = app.getSharedPreferences("background_events", Context.MODE_PRIVATE)
    private fun value(app: Context, id: String, field: String) = prefs(app).getString("$id:$field", "").orEmpty()
    private fun put(app: Context, id: String, field: String, value: String) =
        prefs(app).edit().putString("$id:$field", value).apply()

    fun legacyDevice(app: Context, id: String): String? {
        if (value(app, id, "registered").isEmpty() && value(app, id, "endpoint").isEmpty()) return null
        return runCatching { UUID.fromString(value(app, "", "device")).toString() }.getOrNull()
    }

    fun clearLegacyRegistration(app: Context, id: String) {
        Vault(app).removePushSecret(id)
        prefs(app).edit().remove("$id:endpoint").remove("$id:registered")
            .remove("$id:push_disabled").remove("$id:push_attempt").apply()
    }

    @Synchronized
    fun forget(app: Context, server: String) {
        val store = prefs(app)
        val edit = store.edit()
        store.all.keys.filter { it.startsWith("$server:") }.forEach(edit::remove)
        edit.apply()
        Vault(app).removePushSecret(server)
    }

    fun currentMarker(app: Context, id: String): String? = prefs(app).getString("$id:digest", null)

    @Synchronized
    fun resetMarker(app: Context, id: String) {
        prefs(app).edit().remove("$id:digest").apply()
    }

    @Synchronized
    fun marker(app: Context, server: Server, previous: String?, id: String) {
        if (!server.background || previous != currentMarker(app, server.id)) return
        if (previous == null || id.isNotEmpty() && previous != id && publish(app, server, id))
            put(app, server.id, "digest", id)
    }

    private fun publish(app: Context, server: Server, id: String): Boolean {
        val seen = runCatching { JSONArray(value(app, server.id, "seen")).strings() }.getOrDefault(emptyList())
        if (id in seen) return true
        val posted = try {
            notify(app, server.id, server.name, ui(R.string.digest_ready))
        } catch (_: SecurityException) {
            false
        }
        if (posted) put(app, server.id, "seen", JSONArray((seen + id).takeLast(32)).toString())
        return posted
    }

    private const val CHANNEL = "horizon"

    fun deliveryAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun removeLegacyWaitingNotice(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(0x484f5651)
    }

    private fun notify(context: Context, server: String, title: String, text: String): Boolean {
        if (!deliveryAllowed(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            ui(R.string.hovq_digest_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = ui(R.string.hovq_digest_channel_detail)
            })
        val target = Intent(context, MainActivity::class.java)
            .setData(Uri.parse("horizon-manager://server/$server"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val intent = PendingIntent.getActivity(context, 0, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(server.hashCode(), NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_hovq_notification).setContentTitle(title).setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent).setAutoCancel(true).build())
        return true
    }

    suspend fun record(app: Context, server: String, code: String, trace: String) = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val stamp = now.epochSecond * 1_000_000 + now.nano / 1000
        val key = "$server:background_error:$code"
        val last = prefs(app).getLong(key, 0)
        if (now.epochSecond - last < 3600) return@withContext
        val data = JSONObject().put("time", now.toString()).put("component", "android")
            .put("level", "WARNING").put("code", code).put("detail", "background · $trace")
        ManagerDb.get(app).cache().saveEvents(listOf(LocalEvent(server, "android", stamp, now.toString(), data.toString())))
        prefs(app).edit().putLong(key, now.epochSecond).apply()
    }
}
