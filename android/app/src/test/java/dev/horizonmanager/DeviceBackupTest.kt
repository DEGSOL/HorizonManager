package dev.horizonmanager

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceBackupTest {
    @Test
    fun exportsEncryptedSettingsAndRejectsWrongPasswordOrTampering() {
        val server = Server("0259c74e-4545-482e-84a6-cc96d8260903", "Fixture", "example.invalid",
            22, "reader", true, "private-test-value", "", "SHA256:test", "0".repeat(64), installed = true)
        val input = DeviceBackup.Settings(listOf(server), server.id,
            NewsOrder.Issue, LauncherAppearance.Dark)
        val password = "test-fixture-password"
        val cipher = DeviceBackup.pack(input, password)
        assertFalse(Base64.getDecoder().decode(cipher).decodeToString().contains(server.credential))
        val output = DeviceBackup.unpack(cipher, password)
        assertEquals(listOf(server), output.servers)
        assertEquals(input.selected, output.selected)
        assertEquals(input.order, output.order)
        assertEquals(LauncherAppearance.Dark, output.icon)
        assertThrows(Problem::class.java) { DeviceBackup.unpack(cipher, "another-password") }
        val altered = Base64.getDecoder().decode(cipher)
        altered[altered.lastIndex] = (altered.last().toInt() xor 1).toByte()
        assertThrows(Problem::class.java) {
            DeviceBackup.unpack(Base64.getEncoder().encodeToString(altered), password)
        }
    }
}
