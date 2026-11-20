package dev.horizonmanager

import android.content.Context
import android.net.Uri
import java.util.Base64
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal const val MAX_RESPONSE = 20 * 1024 * 1024
internal class Problem(val code: String, val detail: String = "") : Exception(code)
internal class HostConfirmation(val fingerprint: String, val previous: String) : Exception()

internal data class Server(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val keyMode: Boolean,
    val credential: String,
    val passphrase: String,
    val pin: String,
    val token: String,
    val installed: Boolean = false,
    val https: String = "",
    val background: Boolean = false,
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("host", host)
        .put("port", port).put("user", user).put("key", keyMode)
        .put("credential", credential).put("passphrase", passphrase).put("pin", pin)
        .put("token", token).put("installed", installed).put("https", https).put("background", background)

    companion object {
        fun from(json: JSONObject) = Server(
            json.getString("id"), json.getString("name"), json.getString("host"),
            json.getInt("port"), json.getString("user"), json.getBoolean("key"),
            json.getString("credential"), json.optString("passphrase"), json.getString("pin"),
            json.getString("token"), json.optBoolean("installed"), json.optString("https"),
            json.optBoolean("background")
        )
    }
}

private class HostPin(private val expected: String) : HostKeyRepository {
    var received = ""
        private set

    override fun check(host: String, key: ByteArray): Int {
        val sha = MessageDigest.getInstance("SHA-256").digest(key)
        received = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(sha)
        return when {
            expected.isEmpty() -> HostKeyRepository.NOT_INCLUDED
            expected == received -> HostKeyRepository.OK
            else -> HostKeyRepository.CHANGED
        }
    }

    override fun add(key: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID() = "Horizon Manager"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

internal object Ssh {
    private val deadlines = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Horizon SSH deadlines").apply { isDaemon = true }
    }

    init {
        if (runCatching { java.security.Signature.getInstance("Ed25519") }.isFailure) {
            JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        }
        if (runCatching { javax.crypto.KeyAgreement.getInstance("X25519") }.isFailure) {
            JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        }
    }

    private fun connect(server: Server): Session {
        val jsch = JSch()
        val repository = HostPin(server.pin)
        jsch.hostKeyRepository = repository
        val session = jsch.getSession(server.user, server.host, server.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", if (server.keyMode) "publickey" else "password,keyboard-interactive")
        session.setConfig("NumberOfPasswordPrompts", "1")
        session.setConfig("MaxAuthTries", "1")
        try {
            if (server.keyMode) {
                try {
                    jsch.addIdentity("horizon-user-key", server.credential.toByteArray(), null,
                        server.passphrase.takeIf { it.isNotEmpty() }?.toByteArray())
                } catch (_: Exception) {
                    throw Problem("SSH_KEY")
                }
            } else {
                session.setPassword(server.credential)
            }
            session.setServerAliveInterval(10_000)
            session.setServerAliveCountMax(2)
            session.connect(10_000)
            return session
        } catch (error: Exception) {
            session.disconnect()
            if (repository.received.isNotEmpty() && repository.received != server.pin) {
                throw HostConfirmation(repository.received, server.pin)
            }
            if (error is Problem) throw error
            val description = error.message.orEmpty()
            val code = when {
                description.contains("Auth", true) -> "SSH_AUTH"
                generateSequence<Throwable>(error) { it.cause }.any { it is java.net.UnknownHostException } -> "SSH_DNS"
                generateSequence<Throwable>(error) { it.cause }.any { it is java.net.SocketTimeoutException } ||
                    description.contains("timeout", true) -> "SSH_TIMEOUT"
                description.contains("refused", true) -> "SSH_REFUSED"
                else -> "SSH_CONNECT"
            }
            throw Problem(code)
        }
    }

    fun <T> withSession(server: Server, block: (Session) -> T): T {
        val session = connect(server)
        return try { block(session) } finally { session.disconnect() }
    }

    fun exec(session: Session, command: String, input: ByteArray? = null): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        if (input != null) channel.setInputStream(ByteArrayInputStream(input))
        val stream = channel.inputStream
        val timeout = deadlines.schedule({ channel.disconnect() }, 30, TimeUnit.SECONDS)
        return try {
            channel.connect(10_000)
            val output = stream.readBounded(128 * 1024).decodeToString().trim()
            repeat(30) { if (channel.exitStatus < 0 && !channel.isClosed) Thread.sleep(20) }
            if (channel.exitStatus != 0) throw Problem("SSH_COMMAND", channel.exitStatus.toString())
            output
        } finally {
            timeout.cancel(false)
            channel.disconnect()
        }
    }

    fun probe(context: Context, session: Session): Map<String, String> =
        exec(session, "sh -s", context.assets.open("probe.sh").use { it.readBytes() })
            .lineSequence().mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }.toMap()

    fun install(context: Context, session: Session, server: Server, privileges: String, sudo: String) {
        val directory = exec(session, "umask 077; mktemp -d /tmp/horizon-manager.XXXXXXXXXX")
        if (!Regex("/tmp/horizon-manager\\.[A-Za-z0-9]{10}").matches(directory)) throw Problem("SSH_STAGE")
        try {
            val files = session.openChannel("sftp") as ChannelSftp
            val timeout = deadlines.schedule({ files.disconnect() }, 30, TimeUnit.SECONDS)
            try {
                files.connect(10_000)
                for (name in listOf("install.sh", "launch.sh", "bundle.zip")) {
                    context.assets.open(name).use { input -> files.put(input, "$directory/$name") }
                    files.chmod(0x180, "$directory/$name")
                }
                ByteArrayInputStream((server.token + "\n").toByteArray()).use { files.put(it, "$directory/token") }
                files.chmod(0x180, "$directory/token")
            } finally {
                timeout.cancel(false)
                files.disconnect()
            }
            val command = "/bin/bash '$directory/launch.sh' '$directory'"
            when (privileges) {
                "root" -> exec(session, command)
                "sudo" -> exec(session, "sudo -n -- $command")
                else -> {
                    if (sudo.isEmpty() || '\n' in sudo || '\r' in sudo) throw Problem("SUDO_REQUIRED")
                    exec(session, "sudo -S -p '' -- $command", (sudo + "\n").toByteArray())
                }
            }
        } finally {
            runCatching { exec(session, "rm -rf -- '$directory'") }
        }
    }

    fun http(session: Session, token: String, method: String, path: String, data: ByteArray): Pair<Int, String> {
        val channel = session.openChannel("direct-tcpip") as ChannelDirectTCPIP
        channel.setHost("127.0.0.1")
        channel.setPort(18777)
        channel.setOrgIPAddress("127.0.0.1")
        channel.setOrgPort(0)
        val input = BufferedInputStream(channel.inputStream)
        val output = channel.outputStream
        val timeout = deadlines.schedule({ channel.disconnect() }, 30, TimeUnit.SECONDS)
        return try {
            channel.connect(7_000)
            output.write(("$method /v1/$path HTTP/1.0\r\nHost: 127.0.0.1:18777\r\n" +
                "Authorization: Bearer $token\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${data.size}\r\nConnection: close\r\n\r\n").toByteArray())
            output.write(data)
            output.flush()
            val status = input.asciiLine().split(' ').getOrNull(1)?.toIntOrNull()
                ?: throw Problem("AGENT_UNREACHABLE")
            var length: Int? = null
            for (i in 0..48) {
                val header = input.asciiLine()
                if (header.isEmpty()) break
                if (header.startsWith("content-length:", ignoreCase = true))
                    length = header.substringAfter(':').trim().toIntOrNull()
                if (i == 48) throw Problem("AGENT_PROTOCOL")
            }
            val size = length ?: throw Problem("AGENT_PROTOCOL")
            if (size !in 0..MAX_RESPONSE) throw Problem("RESPONSE_LIMIT")
            val result = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(result, offset, size - offset)
                if (count < 0) throw Problem("AGENT_PROTOCOL")
                offset += count
            }
            status to result.decodeToString()
        } catch (error: Problem) {
            throw error
        } catch (_: Exception) {
            throw Problem("AGENT_UNREACHABLE")
        } finally {
            timeout.cancel(false)
            channel.disconnect()
        }
    }
}

internal class AgentApi(private val server: Server, private val session: Session? = null) {
    fun get(path: String) = request("GET", path)
    fun post(path: String, body: JSONObject = JSONObject()) = request("POST", path, body)
    fun put(path: String, body: JSONObject) = request("PUT", path, body)
    fun delete(path: String) = request("DELETE", path)

    private fun request(method: String, path: String, value: JSONObject? = null): Any {
        check(Regex("[a-zA-Z0-9/_=&?.-]+").matches(path) && ".." !in path)
        val body = value?.toString()?.toByteArray() ?: ByteArray(0)
        val (status, data) = if (server.https.isNotEmpty()) {
            val request = Request.Builder().url(server.https.trimEnd('/') + "/v1/$path")
                .header("Authorization", "Bearer ${server.token}")
                .method(method, if (value != null) body.toRequestBody("application/json".toMediaType()) else null)
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    response.code to (response.body?.byteStream()?.readBounded(MAX_RESPONSE)?.decodeToString().orEmpty())
                }
            } catch (_: javax.net.ssl.SSLException) {
                throw Problem("TLS_INVALID")
            } catch (error: java.io.IOException) {
                throw Problem("HTTPS_UNREACHABLE")
            }
        } else {
            Ssh.http(requireNotNull(session), server.token, method, path, body)
        }
        val json = runCatching { JSONTokener(data).nextValue() }.getOrNull()
        if (status !in 200..299) {
            val detail = (json as? JSONObject)?.optJSONObject("detail")
            throw Problem(detail?.optString("code")?.ifBlank { null } ?: "API_$status",
                detail?.optJSONArray("fields")?.let { fields -> (0 until fields.length()).joinToString { fields.getString(it) } }.orEmpty())
        }
        return json ?: throw Problem("AGENT_PROTOCOL")
    }

    companion object {
        private val httpClient = OkHttpClient.Builder().callTimeout(35, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()

        fun validHttps(value: String): Boolean {
            if (value.isBlank()) return true
            val uri = Uri.parse(value)
            return uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
                uri.query == null && uri.fragment == null && uri.path.orEmpty() in listOf("", "/")
        }
    }
}

internal fun InputStream.readBounded(max: Int): ByteArray {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count == -1) return bytes.toByteArray()
        if (bytes.size() + count > max) throw Problem("RESPONSE_LIMIT")
        bytes.write(buffer, 0, count)
    }
}

private fun InputStream.asciiLine(): String {
    val line = ByteArrayOutputStream()
    repeat(8192) {
        val next = read()
        if (next == -1) throw Problem("AGENT_UNREACHABLE")
        if (next == 10) return line.toByteArray().decodeToString().trimEnd('\r')
        line.write(next)
    }
    throw Problem("AGENT_PROTOCOL")
}
