package dev.horizonmanager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
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

    private fun read(slot: String, bound: Boolean = false): String? {
        val encoded = storage.getString(slot, null) ?: return null
        val data = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            if (bound) updateAAD(slot.toByteArray())
        }
        return cipher.doFinal(data.copyOfRange(12, data.size)).decodeToString()
    }

    private fun write(slot: String, value: String?, bound: Boolean = false) {
        val editor = storage.edit()
        if (value == null) {
            editor.remove(slot)
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key())
                if (bound) updateAAD(slot.toByteArray())
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

    fun pushSecret(server: String): String? = synchronized(LOCK) { read("push:$server", bound = true) }

    fun savePushSecret(server: String, value: String) = synchronized(LOCK) {
        write("push:$server", value, bound = true)
    }

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
    suspend fun saveDigest(digest: CachedDigest)

    @Query("SELECT * FROM CachedDigest WHERE server = :server AND run = :run AND language = :language ORDER BY offset")
    suspend fun digest(server: String, run: String, language: String): List<CachedDigest>

    @Query("SELECT * FROM ArticleFlag WHERE server = :server")
    fun flags(server: String): Flow<List<ArticleFlag>>

    @Query("SELECT * FROM ArticleFlag WHERE server = :server AND article = :article")
    suspend fun articleFlag(server: String, article: String): ArticleFlag?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveFlag(flag: ArticleFlag)

    @Transaction
    suspend fun updateFlag(server: String, article: String, saved: Boolean?, read: Boolean?) {
        val previous = articleFlag(server, article)
        saveFlag(ArticleFlag(server, article, saved ?: previous?.saved ?: false, read ?: previous?.read ?: false))
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

@Database(entities = [CachedDigest::class, ArticleFlag::class, LocalEvent::class, CachedArchive::class],
    version = 1, exportSchema = false)
internal abstract class ManagerDb : RoomDatabase() {
    abstract fun cache(): Cache

    companion object {
        @Volatile private var instance: ManagerDb? = null
        fun get(context: Context): ManagerDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ManagerDb::class.java, "horizon.db")
                .build().also { instance = it }
        }
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
internal fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
internal fun JSONObject.copyObject() = JSONObject(toString())
