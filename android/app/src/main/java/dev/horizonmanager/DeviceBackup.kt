package dev.horizonmanager

import android.content.Context
import org.bouncycastle.crypto.generators.SCrypt
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object DeviceBackup {
    private val header = "HOVQ-ANDROID-BACKUP\u0001".toByteArray()
    private val random = SecureRandom()
    private const val LIMIT = 9_000_000
    private const val SALT = 16
    private const val NONCE = 12

    class Settings(
        val servers: List<Server>,
        val selected: String,
        val order: NewsOrder,
        val icon: LauncherAppearance,
    )

    private fun cipher(mode: Int, password: String, salt: ByteArray, nonce: ByteArray): Cipher {
        val input = password.toByteArray()
        val key = try {
            SCrypt.generate(input, salt, 32768, 8, 1, 32)
        } finally {
            input.fill(0)
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(header)
            }
        } finally {
            key.fill(0)
        }
    }

    fun pack(settings: Settings, password: String): String {
        val payload = JSONObject()
            .put("servers", JSONArray(settings.servers.map(Server::json)))
            .put("selected", settings.selected).put("theme", "graphite")
            .put("order", settings.order.name).put("icon", settings.icon.name)
            .toString().toByteArray()
        val salt = ByteArray(SALT).also(random::nextBytes)
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        return try {
            val data = header + salt + nonce + cipher(Cipher.ENCRYPT_MODE, password, salt, nonce).doFinal(payload)
            val encoded = Base64.getEncoder().encodeToString(data)
            if (encoded.length > LIMIT) throw Problem("BACKUP_INVALID")
            encoded
        } finally {
            payload.fill(0)
        }
    }

    fun unpack(encoded: String, password: String): Settings = try {
        require(encoded.length <= LIMIT)
        val data = Base64.getDecoder().decode(encoded)
        val start = header.size
        val body = start + SALT + NONCE
        require(data.size > body + 16 && data.copyOfRange(0, start).contentEquals(header))
        val plaintext = cipher(Cipher.DECRYPT_MODE, password, data.copyOfRange(start, start + SALT),
            data.copyOfRange(start + SALT, body)).doFinal(data, body, data.size - body)
        val payload = try {
            JSONObject(plaintext.decodeToString())
        } finally {
            plaintext.fill(0)
        }
        val servers = payload.getJSONArray("servers").objects().map(Server::from)
        val ids = servers.map { it.id }.toSet()
        require(ids.size == servers.size && servers.all(::validServer))
        val selected = payload.getString("selected")
        require(selected.isEmpty() || selected in ids)
        Settings(servers, selected,
            NewsOrder.valueOf(payload.getString("order")), LauncherAppearance.valueOf(payload.getString("icon")))
    } catch (_: Exception) {
        throw Problem("BACKUP_INVALID")
    }

    private fun validServer(server: Server) =
        UUID.fromString(server.id).toString() == server.id &&
            server.host.matches(Regex("[A-Za-z0-9:._%\\-]+")) &&
            server.port in 1..65535 && server.user.matches(Regex("[A-Za-z0-9._-]{1,64}")) &&
            server.credential.isNotBlank() && server.credential.length <= 65_536 &&
            server.token.matches(Regex("[a-f0-9]{64}")) &&
            (!server.installed || server.pin.isNotEmpty()) && AgentApi.validHttps(server.https)

    fun read(context: Context, servers: List<Server>, selected: String) = Settings(servers, selected,
        readingOrder(context), LauncherAppearance.current(context))

    fun readingOrder(context: Context): NewsOrder =
        context.getSharedPreferences("reading", Context.MODE_PRIVATE).getString("default_order", null)
            ?.let { id -> NewsOrder.entries.firstOrNull { it.name == id } } ?: NewsOrder.Issue
}
