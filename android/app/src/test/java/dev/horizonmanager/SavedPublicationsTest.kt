package dev.horizonmanager

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedPublicationsTest {
    @Test
    fun restoresFavoriteSnapshotsOnACleanDeviceWithAFourDigitPassword() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = UUID.randomUUID().toString()
        val snapshot = SavedPublication(server, "favorite", "past", "en", 100L,
            JSONObject().put("id", "favorite").put("title", "Saved offline").toString())
        val favorite = FavoritePublication(snapshot, true)
        val exported = Room.inMemoryDatabaseBuilder(context, ManagerDb::class.java).build().use { database ->
            val cache = database.cache()
            cache.updateFlag(server, "favorite", saved = true, read = true, publication = snapshot)
            val settings = DeviceBackup.Settings(emptyList(), "", NewsOrder.Issue, LauncherAppearance.Dark, cache.backupFavorites())
            DeviceBackup.pack(settings, "1234")
        }
        Room.inMemoryDatabaseBuilder(context, ManagerDb::class.java).build().use { database ->
            val cache = database.cache()
            val restored = DeviceBackup.unpack(exported, "1234")
            cache.restoreFavorites(restored.favorites)
            cache.restoreFavorites(restored.favorites)
            assertEquals(favorite, cache.favorites().first().single())
            assertEquals(listOf("favorite"), cache.savedIds(server))
        }
        Unit
    }

    @Test
    fun upgradesCachedV1FavoritesAndRetainsThemWhenAnIssueIsDeletedAndResynced() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "legacy-${UUID.randomUUID()}.db"
        val dbPath = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        val oldArticle = JSONObject().put("id", "a").put("body", "Full body from the previous version.")
        val page = JSONObject().put("items", JSONArray(listOf(oldArticle))).toString()

        SQLiteDatabase.openOrCreateDatabase(dbPath, null).use { old ->
            old.execSQL("CREATE TABLE CachedDigest (server TEXT NOT NULL, run TEXT NOT NULL, language TEXT NOT NULL, " +
                "`offset` INTEGER NOT NULL, data TEXT NOT NULL, PRIMARY KEY(server, run, language, `offset`))")
            old.execSQL("CREATE TABLE ArticleFlag (server TEXT NOT NULL, article TEXT NOT NULL, saved INTEGER NOT NULL, " +
                "read INTEGER NOT NULL, PRIMARY KEY(server, article))")
            old.execSQL("CREATE TABLE CachedArchive (server TEXT NOT NULL PRIMARY KEY, data TEXT NOT NULL)")
            old.execSQL("CREATE TABLE LocalEvent (server TEXT NOT NULL, componentId TEXT NOT NULL, eventId INTEGER NOT NULL, " +
                "timestamp TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(server, componentId, eventId))")
            old.execSQL("INSERT INTO CachedDigest VALUES ('server', 'past', 'en', 0, ?)", arrayOf(page))
            old.execSQL("INSERT INTO ArticleFlag VALUES ('server', 'a', 1, 1)")
            old.execSQL("INSERT INTO CachedArchive VALUES ('server', '[{\"id\":\"past\"}]')")
            old.version = 1
        }
        fun open() = Room.databaseBuilder(context, ManagerDb::class.java, name)
            .addMigrations(ManagerDb.migrate1To2).build()
        try {
            val upgraded = open()
            try {
                val cache = upgraded.cache()
                assertTrue(cache.articleFlag("server", "a")!!.saved)
                assertTrue(cache.favorites().first().single().read)
                assertTrue(cache.archive("server")!!.contains("past"))
                cache.deleteDigest("server", "past")
                cache.saveDigest(CachedDigest("server", "past", "en", 0, page))
                assertTrue(cache.digest("server", "past", "en").isEmpty())
            } finally {
                upgraded.close()
            }
            val reopened = open()
            try {
                val cache = reopened.cache()
                assertEquals(listOf("a"), cache.readArticleIds("server").first())
                val favorite = cache.favorites().first().single().snapshot
                assertEquals(oldArticle.getString("body"), favorite.publication().getString("body"))
                val hidden = cache.hiddenDigests("server").toSet()
                val remoteIssue = JSONObject().put("id", "past").put("ready_at", "2025-11-01T00:00:00Z")
                assertTrue(splitDigests(listOf(remoteIssue), 24, Instant.parse("2026-11-01T00:00:00Z"),
                    hidden = hidden).archive.isEmpty())
                val newer = favorite.copy(article = "b", savedAt = 100L)
                cache.updateFlag("server", "b", saved = true, read = false, publication = newer)
                assertEquals(listOf("b", "a"), cache.favorites().first().map { it.snapshot.article })
                cache.updateFlag("server", "a", saved = false, read = null)
                assertEquals(listOf("b"), cache.favorites().first().map { it.snapshot.article })
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }
}
