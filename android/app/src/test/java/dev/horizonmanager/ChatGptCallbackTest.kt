package dev.horizonmanager

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatGptCallbackTest {
    @Test(timeout = 10_000)
    fun browserCallbackRejectsWrongStateBeforeAcceptingTheMatchingCode() {
        val address = InetAddress.getByName("127.0.0.1")
        val port = ServerSocket(0, 1, address).use { it.localPort }
        val state = "oauth-fixture-state".repeat(3)
        val authorization = Uri.parse("https://auth.openai.com/api/accounts/authorize").buildUpon()
            .appendQueryParameter("redirect_uri", "http://127.0.0.1:$port/auth/callback")
            .appendQueryParameter("state", state)
            .build().toString()
        fun browserRequest(target: String): String = Socket(address, port).use { browser ->
            browser.soTimeout = 3000
            browser.getOutputStream().write(
                "GET $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray()
            )
            browser.getInputStream().bufferedReader().use { it.readText() }
        }
        ChatGptCallback(authorization).use { listener ->
            runBlocking {
                val returned = async(Dispatchers.IO) { listener.await() }
                val rejected = browserRequest("/auth/callback?state=wrong&code=incorrect")
                assertEquals("HTTP/1.1 400 Bad Request", rejected.lineSequence().first())
                val accepted = browserRequest("/auth/callback?state=$state&code=fixture%2Bcode&client_id=fixture-client")
                assertEquals("HTTP/1.1 200 OK", accepted.lineSequence().first())
                assertFalse(accepted.contains("fixture+code"))
                val callback = returned.await()
                assertEquals(state, callback.getString("state"))
                assertEquals("fixture+code", callback.getString("code"))
                assertEquals("fixture-client", callback.getString("client_id"))
            }
        }
    }
}
