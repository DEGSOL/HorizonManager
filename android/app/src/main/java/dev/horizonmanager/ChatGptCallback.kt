package dev.horizonmanager

import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest

/** The browser reaches this listener on Android. The authenticated agent API relays the result. */
internal class ChatGptCallback(authorizationUrl: String) : Closeable {
    private val url = Uri.parse(authorizationUrl)
    private val redirect = Uri.parse(url.getQueryParameter("redirect_uri").orEmpty())
    private val state = url.getQueryParameter("state").orEmpty()
    private val server = ServerSocket()

    init {
        try {
            if (url.scheme != "https" || url.authority != "auth.openai.com" || url.path != "/api/accounts/authorize" ||
                redirect.scheme != "http" || redirect.host != "127.0.0.1" || redirect.path != "/auth/callback" ||
                redirect.port !in 1024..65535 || redirect.userInfo != null || redirect.query != null ||
                !state.matches(Regex("[A-Za-z0-9_-]{32,128}"))) {
                throw Problem("CHATGPT_CALLBACK_INVALID")
            }
            server.bind(InetSocketAddress("127.0.0.1", redirect.port), 4)
            server.soTimeout = 1000
        } catch (error: Exception) {
            server.close()
            if (error is IOException) throw Problem("CHATGPT_CALLBACK_PORT", cause = error)
            throw error
        }
    }

    suspend fun await(): JSONObject = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + 165_000
        while (SystemClock.elapsedRealtime() < deadline) {
            currentCoroutineContext().ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                val callback = try {
                    socket.soTimeout = 1000
                    readCallback(socket.getInputStream().buffered())
                } catch (_: IOException) {
                    null
                }
                runCatching {
                    val message = ui(if (callback == null) R.string.hovq_chatgpt_callback_error else R.string.hovq_chatgpt_return)
                    val bytes = message.toByteArray()
                    val status = if (callback == null) "400 Bad Request" else "200 OK"
                    val response = socket.getOutputStream()
                    response.write(("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                        "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n" +
                        "Content-Length: ${bytes.size}\r\n\r\n").toByteArray())
                    response.write(bytes)
                    response.flush()
                }
                if (callback != null) return@withContext callback
            }
        }
        throw Problem("CHATGPT_LOGIN_EXPIRED")
    }

    private fun readCallback(input: BufferedInputStream): JSONObject? {
        var budget = 16_384
        fun line(): String {
            val text = StringBuilder()
            while (budget-- > 0) {
                val byte = input.read()
                if (byte < 0) throw IOException("Incomplete callback")
                if (byte == 10) return text.toString().trimEnd('\r')
                text.append(byte.toChar())
            }
            throw IOException("Callback too large")
        }

        val request = line().split(' ', limit = 3)
        if (request.size != 3 || request[0] != "GET" || !request[1].startsWith('/')) return null
        var host = ""
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            if (header.substringBefore(':').equals("Host", ignoreCase = true)) host = header.substringAfter(':').trim()
        }
        if (host != "127.0.0.1:${redirect.port}") return null
        val uri = Uri.parse("http://$host${request[1]}")
        if (uri.path != "/auth/callback") return null
        val givenState = uri.getQueryParameters("state").singleOrNull() ?: return null
        if (!MessageDigest.isEqual(state.toByteArray(), givenState.toByteArray())) return null
        val codes = uri.getQueryParameters("code")
        val errors = uri.getQueryParameters("error")
        val clients = uri.getQueryParameters("client_id")
        if (codes.size + errors.size != 1 || clients.size > 1) return null
        val callback = JSONObject().put("state", state)
        codes.singleOrNull()?.let { callback.put("code", it) }
        errors.singleOrNull()?.let { callback.put("error", it) }
        clients.singleOrNull()?.let { callback.put("client_id", it) }
        return callback
    }

    override fun close() = server.close()
}
