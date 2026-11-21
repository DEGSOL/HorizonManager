package dev.horizonmanager

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class DigestPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        if (endpoint.temporary) return
        DigestNotifications.setEndpoint(this, instance, endpoint.url)
        DigestWorker.sync(this, instance)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (message.content.size !in 99..120) return
        val server = runCatching { Vault(this).load().find { it.id == instance && it.background } }.getOrNull() ?: return
        val secret = runCatching { DigestNotifications.existingSecret(this, instance) }.getOrNull() ?: return
        val packet = message.content.toString(Charsets.US_ASCII)
        val id = verifiedDigest(packet, secret, Instant.now().epochSecond) ?: return
        DigestNotifications.push(this, server, id)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) = onUnregistered(instance)

    override fun onUnregistered(instance: String) {
        DigestNotifications.setEndpoint(this, instance, "")
        DigestWorker.sync(this, instance)
    }
}

internal fun verifiedDigest(message: String, secret: String, now: Long): String? {
    val parts = message.split('.')
    if (parts.size != 3 || !parts[0].matches(Regex("[a-f0-9]{32}")) ||
        !parts[2].matches(Regex("[a-f0-9]{64}"))) return null
    val sent = parts[1].toLongOrNull() ?: return null
    if (sent !in (now - 86_400)..(now + 300)) return null
    val key = secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    val expected = mac.doFinal(message.substringBeforeLast('.').toByteArray(Charsets.US_ASCII))
    val actual = parts[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    return parts[0].takeIf { MessageDigest.isEqual(expected, actual) }
}

internal object DigestNotifications {
    private fun prefs(app: Context) = app.getSharedPreferences("background_events", Context.MODE_PRIVATE)
    private fun value(app: Context, id: String, field: String) = prefs(app).getString("$id:$field", "").orEmpty()
    private fun put(app: Context, id: String, field: String, value: String) =
        prefs(app).edit().putString("$id:$field", value).apply()

    @Synchronized
    fun device(app: Context): String = value(app, "", "device").ifEmpty {
        UUID.randomUUID().toString().also { put(app, "", "device", it) }
    }

    @Synchronized
    fun secret(app: Context, id: String): String = existingSecret(app, id) ?: ByteArray(32).also {
        SecureRandom().nextBytes(it)
    }.joinToString("") { "%02x".format(it) }.also { Vault(app).savePushSecret(id, it) }

    fun existingSecret(app: Context, id: String) = Vault(app).pushSecret(id)
    fun endpoint(app: Context, id: String) = value(app, id, "endpoint")
    fun registration(app: Context, id: String) = value(app, id, "registered")
    fun hasPush(app: Context, id: String) = endpoint(app, id).let { it.isNotEmpty() && it == registration(app, id) }
    fun observeRegistration(app: Context, id: String) = callbackFlow {
        val storage = prefs(app)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "$id:registered" || key == "$id:endpoint") trySend(hasPush(app, id))
        }
        storage.registerOnSharedPreferenceChangeListener(listener)
        trySend(hasPush(app, id))
        awaitClose { storage.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun setEndpoint(app: Context, id: String, value: String) = put(app, id, "endpoint", value)
    fun setRegistered(app: Context, id: String, value: String) {
        put(app, id, "registered", value)
        prefs(app).edit().remove("$id:last_reconcile").apply()
    }

    fun checkDue(app: Context, id: String, endpoint: String, registered: String): Boolean {
        if (endpoint.isEmpty() || endpoint != registered) return true
        val now = System.currentTimeMillis()
        val key = "$id:last_reconcile"
        val last = prefs(app).getLong(key, 0)
        return now - last !in 0 until 5 * 60 * 60 * 1000L
    }

    fun reconciled(app: Context, id: String) {
        prefs(app).edit().putLong("$id:last_reconcile", System.currentTimeMillis()).apply()
    }

    fun register(app: Context, distributor: String, server: String) {
        UnifiedPush.saveDistributor(app, distributor)
        UnifiedPush.register(app, instance = server)
    }

    fun unregister(app: Context, server: String) {
        UnifiedPush.unregister(app, instance = server)
        setEndpoint(app, server, "")
    }

    fun forget(app: Context, server: String) {
        try {
            unregister(app, server)
        } finally {
            val store = prefs(app)
            val edit = store.edit()
            store.all.keys.filter { it.startsWith("$server:") }.forEach(edit::remove)
            edit.apply()
            Vault(app).removePushSecret(server)
        }
    }

    @Synchronized
    fun marker(app: Context, server: Server, id: String) {
        if (!server.background) return
        val key = server.id + ":digest"
        val previous = prefs(app).getString(key, null)
        if (id.isNotEmpty() && previous != null && previous != id) publish(app, server, id)
        if (previous == null || id.isNotEmpty() && (previous == id || deliveryAllowed(app)))
            prefs(app).edit().putString(key, id).apply()
    }

    @Synchronized
    fun push(app: Context, server: Server, id: String) {
        publish(app, server, id)
    }

    private fun publish(app: Context, server: Server, id: String) {
        val seen = runCatching { JSONArray(value(app, server.id, "seen")).strings() }.getOrDefault(emptyList())
        if (id in seen) return
        if (notify(app, server.id, server.name, ui(R.string.digest_ready))) {
            put(app, server.id, "seen", JSONArray((seen + id).takeLast(32)).toString())
        }
    }

    private const val CHANNEL = "horizon"

    fun deliveryAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun notify(context: Context, server: String, title: String, text: String): Boolean {
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
