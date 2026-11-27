package dev.horizonmanager

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.io.IOException

internal object BackupDocument {
    // A system document picker can outlive the app process. Only the encrypted backup
    // or journal export is staged, in a private file excluded from Android backups.
    private fun pending(context: Context) = AtomicFile(File(context.noBackupFilesDir, "pending-document"))

    fun stage(context: Context, content: String) {
        val file = pending(context)
        val stream = file.startWrite()
        try {
            stream.write(content.toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun discard(context: Context) = pending(context).delete()

    fun writeTo(context: Context, uri: Uri) {
        val file = pending(context)
        try {
            file.openRead().use { input ->
                (context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("Open export document"))
                    .use { output -> input.copyTo(output) }
            }
        } finally {
            file.delete()
        }
    }

    fun read(context: Context, uri: Uri): String = try {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Open backup document")
        input.use { it.readBounded(DeviceBackup.MAX_FILE_SIZE).decodeToString().trimStart('\uFEFF').trim() }
    } catch (error: Exception) {
        throw Problem("BACKUP_FILE_READ", cause = error)
    }
}
