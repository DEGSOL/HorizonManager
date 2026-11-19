package dev.horizonmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

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
