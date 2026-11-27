package dev.horizonmanager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class Vault(context: Context) {
    private val storage = context.getSharedPreferences("encrypted_servers", Context.MODE_PRIVATE)

    private fun key(): SecretKey = synchronized(LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).run {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private fun read(slot: String): String? {
        val encoded = storage.getString(slot, null) ?: return null
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        }
        return cipher.doFinal(data.copyOfRange(12, data.size)).decodeToString()
    }

    private fun write(slot: String, value: String?) {
        val editor = storage.edit()
        if (value == null) {
            editor.remove(slot)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key())
            }
            val ciphertext = cipher.iv + cipher.doFinal(value.toByteArray())
            editor.putString(slot, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
        if (!editor.commit()) throw Problem("VAULT_WRITE")
    }

    fun load(): List<Server> = synchronized(LOCK) {
        val list = JSONArray(read("servers") ?: return emptyList())
        (0 until list.length()).map { Server.from(list.getJSONObject(it)) }
    }

    fun save(profiles: List<Server>) = synchronized(LOCK) {
        write("servers", JSONArray(profiles.map { it.json() }).toString())
    }

    fun removePushSecret(server: String) = synchronized(LOCK) { write("push:$server", null) }

    fun removeLegacyRouterKeys(profiles: List<Server>) = synchronized(LOCK) {
        storage.edit().apply {
            for (server in profiles) for (router in AiRouter.entries) remove("router:${server.id}:${router.id}")
        }.apply()
    }

    private companion object {
        const val KEY = "horizon-manager-vault"
        val LOCK = Any()
    }
}

@Entity(primaryKeys = ["server", "run", "language", "offset"])
internal data class CachedDigest(val server: String, val run: String, val language: String, val offset: Int, val data: String)

@Entity(primaryKeys = ["server", "article"])
internal data class ArticleFlag(val server: String, val article: String, val saved: Boolean, val read: Boolean)

@Entity(primaryKeys = ["server", "article"])
internal data class SavedPublication(val server: String, val article: String, val run: String,
    val language: String, val savedAt: Long, val data: String) {
    fun publication() = JSONObject(data)
}

internal data class FavoritePublication(@Embedded val snapshot: SavedPublication, val read: Boolean)

@Entity(primaryKeys = ["server", "run"])
internal data class HiddenDigest(val server: String, val run: String)

@Entity(primaryKeys = ["server", "componentId", "eventId"])
internal data class LocalEvent(val server: String, val componentId: String, val eventId: Long, val timestamp: String, val data: String)

@Entity
internal data class CachedArchive(@androidx.room.PrimaryKey val server: String, val data: String)

@Dao
internal interface Cache {
    @Query("SELECT data FROM CachedArchive WHERE server = :server")
    suspend fun archive(server: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveArchive(archive: CachedArchive)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDigest(digest: CachedDigest)

    @Query("SELECT EXISTS(SELECT 1 FROM HiddenDigest WHERE server = :server AND run = :run)")
    suspend fun isHidden(server: String, run: String): Boolean

    @Query("SELECT run FROM HiddenDigest WHERE server = :server")
    suspend fun hiddenDigests(server: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hideDigest(digest: HiddenDigest)

    @Query("DELETE FROM CachedDigest WHERE server = :server AND run = :run")
    suspend fun purgeDigest(server: String, run: String)

    @Transaction
    suspend fun deleteDigest(server: String, run: String) {
        hideDigest(HiddenDigest(server, run))
        purgeDigest(server, run)
    }

    @Query(FAVORITES_QUERY)
    fun favorites(): Flow<List<FavoritePublication>>

    @Query(FAVORITES_QUERY)
    suspend fun backupFavorites(): List<FavoritePublication>

    @Transaction
    suspend fun restoreFavorites(favorites: List<FavoritePublication>) {
        for ((incoming, read) in favorites) {
            val current = savedPublication(incoming.server, incoming.article)
            val flag = articleFlag(incoming.server, incoming.article)
            if (current == null || current.savedAt < incoming.savedAt) savePublication(incoming)
            saveFlag(ArticleFlag(incoming.server, incoming.article, true, read || flag?.read == true))
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePublication(publication: SavedPublication)

    @Query("SELECT * FROM SavedPublication WHERE server = :server AND article = :article")
    suspend fun savedPublication(server: String, article: String): SavedPublication?

    @Query("UPDATE SavedPublication SET data = :data WHERE server = :server AND article = :article AND run = :run AND language = :language")
    suspend fun refreshSavedArticle(server: String, article: String, run: String, language: String, data: String)

    @Query("SELECT article FROM ArticleFlag WHERE server = :server AND saved = 1")
    suspend fun savedIds(server: String): List<String>

    @Query("DELETE FROM SavedPublication WHERE server = :server AND article = :article")
    suspend fun removePublication(server: String, article: String)

    @Transaction
    suspend fun saveDigest(digest: CachedDigest) {
        if (isHidden(digest.server, digest.run)) return
        insertDigest(digest)
        val saved = savedIds(digest.server).toHashSet()
        if (saved.isEmpty()) return
        for (article in digest.publications()) {
            val id = article.optString("id")
            if (id !in saved) continue
            val current = savedPublication(digest.server, id)
            if (current == null || current.run == digest.run && current.language == digest.language) {
                savePublication(SavedPublication(digest.server, id, digest.run, digest.language,
                    current?.savedAt ?: 0, article.toString()))
            }
        }
    }

    @Query("SELECT DISTINCT run FROM CachedDigest WHERE server = :server AND offset = 0")
    fun localIssues(server: String): Flow<List<String>>

    @Transaction
    suspend fun mergeArtwork(server: String, run: String, language: String, artwork: JSONObject) {
        for (cached in digest(server, run, language)) {
            val page = JSONObject(cached.data)
            var changed = false
            for (item in page.getJSONArray("items").objects()) if (!item.has("media")) {
                item.put("media", artwork.optJSONObject(item.optString("id")) ?: JSONObject())
                changed = true
            }
            if (changed) saveDigest(cached.copy(data=page.toString()))
        }
    }

    @Query("SELECT COUNT(DISTINCT run) FROM CachedDigest WHERE server = :server")
    fun cachedIssues(server: String): Flow<Int>

    @Query("SELECT * FROM CachedDigest WHERE server = :server AND run = :run AND language = :language ORDER BY offset")
    suspend fun digest(server: String, run: String, language: String): List<CachedDigest>

    @Query("SELECT data FROM CachedDigest WHERE server = :server AND run = :run AND language = :language ORDER BY offset")
    fun digestPages(server: String, run: String, language: String): Flow<List<String>>

    @Query("SELECT article FROM ArticleFlag WHERE server = :server AND read = 1")
    fun readArticleIds(server: String): Flow<List<String>>

    @Query("SELECT * FROM ArticleFlag WHERE server = :server")
    fun flags(server: String): Flow<List<ArticleFlag>>

    @Query("SELECT * FROM ArticleFlag WHERE server = :server AND article = :article")
    suspend fun articleFlag(server: String, article: String): ArticleFlag?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveFlag(flag: ArticleFlag)

    @Transaction
    suspend fun updateFlag(server: String, article: String, saved: Boolean?, read: Boolean?,
                           publication: SavedPublication? = null) {
        val previous = articleFlag(server, article)
        saveFlag(ArticleFlag(server, article, saved ?: previous?.saved ?: false, read ?: previous?.read ?: false))
        if (saved == false) removePublication(server, article)
        if (saved == true && publication != null) savePublication(publication)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveEvents(events: List<LocalEvent>)

    @Query("SELECT * FROM LocalEvent WHERE server = :server ORDER BY timestamp DESC, eventId DESC LIMIT 1500")
    fun events(server: String): Flow<List<LocalEvent>>

    @Query("SELECT COALESCE(MAX(eventId),0) FROM LocalEvent WHERE server = :server AND componentId = 'remote'")
    suspend fun eventCursor(server: String): Long

    @Query("DELETE FROM LocalEvent WHERE server = :server AND (componentId!='remote' OR eventId <= :before)")
    suspend fun clearEvents(server: String, before: Long)

    @Query("DELETE FROM LocalEvent WHERE server = :server AND timestamp < :before")
    suspend fun pruneEvents(server: String, before: String)
}

private const val FAVORITES_QUERY = "SELECT SavedPublication.*, ArticleFlag.read FROM SavedPublication INNER JOIN ArticleFlag " +
    "ON SavedPublication.server = ArticleFlag.server AND SavedPublication.article = ArticleFlag.article " +
    "WHERE ArticleFlag.saved = 1 ORDER BY SavedPublication.savedAt DESC, SavedPublication.rowid DESC"

@Database(entities = [CachedDigest::class, ArticleFlag::class, LocalEvent::class, CachedArchive::class,
    SavedPublication::class, HiddenDigest::class], version = 2, exportSchema = false)
internal abstract class ManagerDb : RoomDatabase() {
    abstract fun cache(): Cache

    companion object {
        val migrate1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE SavedPublication (server TEXT NOT NULL, article TEXT NOT NULL, " +
                    "run TEXT NOT NULL, language TEXT NOT NULL, savedAt INTEGER NOT NULL, data TEXT NOT NULL, " +
                    "PRIMARY KEY(server, article))")
                db.execSQL("CREATE TABLE HiddenDigest (server TEXT NOT NULL, run TEXT NOT NULL, PRIMARY KEY(server, run))")
                val saved = mutableMapOf<String, MutableSet<String>>()
                db.query("SELECT server, article FROM ArticleFlag WHERE saved = 1").use { rows ->
                    while (rows.moveToNext()) saved.getOrPut(rows.getString(0), ::mutableSetOf).add(rows.getString(1))
                }
                if (saved.isEmpty()) return
                db.query("SELECT server, run, language, data FROM CachedDigest ORDER BY rowid DESC").use { pages ->
                    while (pages.moveToNext()) {
                        val server = pages.getString(0)
                        val remaining = saved[server]?.takeIf { it.isNotEmpty() } ?: continue
                        for (article in digestPublications(pages.getString(3))) {
                            val id = article.optString("id")
                            if (remaining.remove(id)) db.execSQL(
                                "INSERT INTO SavedPublication VALUES (?, ?, ?, ?, 0, ?)",
                                arrayOf(server, id, pages.getString(1), pages.getString(2), article.toString()))
                        }
                    }
                }
            }
        }

        @Volatile private var instance: ManagerDb? = null
        fun get(context: Context): ManagerDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ManagerDb::class.java, "horizon.db")
                .addMigrations(migrate1To2).build().also { instance = it }
        }
    }
}

private fun CachedDigest.publications() = digestPublications(data)
internal fun digestPublications(data: String) =
    runCatching { JSONObject(data).optJSONArray("items")?.objects().orEmpty() }.getOrDefault(emptyList())

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
internal fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
internal fun JSONObject.copyObject() = JSONObject(toString())
