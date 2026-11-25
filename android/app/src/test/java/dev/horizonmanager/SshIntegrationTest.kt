package dev.horizonmanager

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket

class SshIntegrationTest {
    private lateinit var server: Server

    @Before
    fun localFixture() {
        val port = System.getenv("HM_TEST_SSH_PORT")?.toIntOrNull()
        assumeTrue("Run the loopback SSH fixture to exercise the transport", port != null)
        server = Server("test", "test", "127.0.0.1", requireNotNull(port), "horizon",
            false, "fixture-ssh-password", "", "", "1".repeat(64))
    }

    @Test
    fun passwordAndPinnedTunnel() {
        val pin = assertThrows(HostConfirmation::class.java) {
            Ssh.withSession(server) { error("Unverified host") }
        }.fingerprint
        val pinned = server.copy(pin = pin)
        Ssh.withSession(pinned) { session ->
            assertEquals("READY", Ssh.exec(session, "printf READY"))
            val (code, json) = Ssh.http(session, server.token, "GET", "health", byteArrayOf())
            assertEquals(200, code)
            check(json.contains("\"api\":1"))
            val (unauthorized, _) = Ssh.http(session, "0".repeat(64), "GET", "health", byteArrayOf())
            assertEquals(401, unauthorized)
            assertEquals(1, AgentApi(pinned, session).status().getInt("api"))
            val agentFailure = assertThrows(Problem::class.java) {
                AgentApi(pinned.copy(token = "0".repeat(64)), session).status()
            }
            assertEquals("AUTH_REQUIRED", agentFailure.code)
        }
        assertThrows(HostConfirmation::class.java) {
            Ssh.withSession(pinned.copy(pin = "SHA256:incorrect")) { error("Key change accepted") }
        }
        val problem = assertThrows(Problem::class.java) {
            Ssh.withSession(pinned.copy(credential = "bad-password")) { error("Wrong password accepted") }
        }
        assertEquals("SSH_AUTH", problem.code)
    }

    @Test
    fun mediaPolicyRoundTripOverPinnedSsh() {
        val pin = assertThrows(HostConfirmation::class.java) { Ssh.withSession(server) {} }.fingerprint
        val pinned = server.copy(pin=pin)
        Ssh.withSession(pinned) { session ->
            val api = AgentApi(pinned,session)
            val old = (api.get("status") as org.json.JSONObject).getBoolean("media_search")
            try {
                api.put("media-search",org.json.JSONObject().put("enabled",!old))
                assertEquals(!old,(api.get("status") as org.json.JSONObject).getBoolean("media_search"))
            } finally {
                api.put("media-search",org.json.JSONObject().put("enabled",old))
            }
        }
    }

    @Test
    fun theLiveDigestWaitDoesNotBlockAnotherSshOperation() = runBlocking {
        val pin = assertThrows(HostConfirmation::class.java) { Ssh.withSession(server) {} }.fingerprint
        val pinned = server.copy(pin = pin)
        val waiting = CompletableDeferred<Unit>()
        val listener = launch {
            useTransport {
                Ssh.withSession(pinned) { session ->
                    val api = AgentApi(pinned, session)
                    assertEquals("", api.digestNotificationMarker())
                    waiting.complete(Unit)
                    api.digestNotificationMarker("")
                }
            }
        }
        try {
            withTimeout(8_000) { waiting.await() }
            withTimeout(5_000) {
                useTransport(pinned) { Ssh.withSession(pinned) { assertEquals(1, AgentApi(pinned, it).health().getInt("api")) } }
            }
        } finally {
            withTimeout(5_000) { listener.cancelAndJoin() }
        }
    }

    @Test
    fun encryptedPrivateKey() {
        val path = System.getenv("HM_TEST_SSH_KEY")
        assumeTrue(path != null)
        val pin = assertThrows(HostConfirmation::class.java) { Ssh.withSession(server) {} }.fingerprint
        val key = File(requireNotNull(path)).readText()
        Ssh.withSession(server.copy(pin = pin, keyMode = true, credential = key, passphrase = "fixture-key-password")) { session ->
            assertEquals("READY", Ssh.exec(session, "printf READY"))
        }
    }
}

class SshNetworkFailureTest {
    private fun server(port: Int) = Server(
        "loopback", "Loopback", "127.0.0.1", port, "horizon", false, "fixture-password", "", "", "",
    )

    @Test
    fun refusedPortIsNotAnAuthenticationError() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val problem = assertThrows(Problem::class.java) { Ssh.withSession(server(port)) {} }
        assertEquals("SSH_REFUSED", problem.code)
    }

    @Test
    fun cancelClosesSocketDuringSshHandshake() = runBlocking {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            listener.soTimeout = 3_000
            val accepted = CompletableDeferred<Unit>()
            val peer = async(Dispatchers.IO) {
                listener.accept().use { socket ->
                    socket.soTimeout = 3_000
                    accepted.complete(Unit)
                    while (socket.getInputStream().read() != -1) {
                        // Drain the client's identification until cancellation closes the socket.
                    }
                    true
                }
            }
            val profile = server(listener.localPort)
            val attempt = launch {
                useTransport(profile) { Ssh.withSession(profile) { error("Handshake did not complete") } }
            }
            withTimeout(4_000) {
                accepted.await()
                attempt.cancelAndJoin()
                check(peer.await())
            }
        }
    }
}
