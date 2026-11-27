package dev.horizonmanager

import android.net.Uri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupDocumentTest {
    @Test
    fun stagesEncryptedDataUntilAFileDestinationIsReturned() {
        val context = RuntimeEnvironment.getApplication()
        val destination = Uri.fromFile(File(context.cacheDir, "settings.hqb"))
        val password = "test-export-password"
        val settings = DeviceBackup.Settings(emptyList(), "", NewsOrder.Issue, LauncherAppearance.Dark)
        val encrypted = DeviceBackup.pack(settings, password)

        BackupDocument.stage(context, encrypted)
        BackupDocument.writeTo(context.createConfigurationContext(context.resources.configuration), destination)

        val saved = BackupDocument.read(context, destination)
        assertEquals(encrypted, saved)
        assertEquals(LauncherAppearance.Dark, DeviceBackup.unpack(saved, password).icon)
        assertThrows(java.io.FileNotFoundException::class.java) { BackupDocument.writeTo(context, destination) }
    }

    @Test
    fun opensTheSelectedBackupWithANewContext() {
        val context = RuntimeEnvironment.getApplication()
        val chosen = File(context.cacheDir, "import.hqb")
        val password = "test-import-password"
        val settings = DeviceBackup.Settings(emptyList(), "", NewsOrder.Newest, LauncherAppearance.Light)
        val encrypted = DeviceBackup.pack(settings, password)
        chosen.writeText("\uFEFF$encrypted\n")
        val document = Uri.fromFile(chosen)
        val recreatedContext = context.createConfigurationContext(context.resources.configuration)

        val imported = DeviceBackup.unpack(BackupDocument.read(recreatedContext, document), password)
        assertEquals(settings.order, imported.order)
        assertEquals(settings.icon, imported.icon)

        val missing = Uri.fromFile(File(context.cacheDir, "missing.hqb"))
        val error = assertThrows(Problem::class.java) { BackupDocument.read(recreatedContext, missing) }
        assertEquals("BACKUP_FILE_READ", error.code)
    }
}
