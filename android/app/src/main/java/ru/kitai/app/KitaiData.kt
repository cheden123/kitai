package ru.kitai.app

import android.content.Context
import android.content.SharedPreferences
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
import androidx.room.withTransaction
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.OffsetDateTime
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "saved_product", primaryKeys = ["source", "productId"])
data class SavedProduct(
    val source: String,
    val productId: String,
    val url: String,
    val snapshot: String?,
    val expiresAt: String?,
    val favorite: Boolean = false,
    val viewedAt: Long? = null,
    val comparison: Boolean = false,
)

@Dao
interface SavedProductDao {
    @Query("SELECT * FROM saved_product ORDER BY viewedAt DESC, source, productId")
    fun observe(): Flow<List<SavedProduct>>

    @Query("SELECT * FROM saved_product WHERE source = :source AND productId = :id")
    suspend fun find(source: String, id: String): SavedProduct?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(product: SavedProduct)

    @Query("DELETE FROM saved_product WHERE favorite = 0 AND comparison = 0 AND viewedAt IS NULL")
    suspend fun deleteUnused()

    @Query("UPDATE saved_product SET viewedAt = NULL")
    suspend fun clearHistory()

    @Query("UPDATE saved_product SET snapshot = NULL WHERE snapshot IS NOT NULL AND expiresAt <= :now")
    suspend fun purgeExpired(now: String)
}

@Database(entities = [SavedProduct::class], version = 1, exportSchema = false)
abstract class KitaiDatabase : RoomDatabase() {
    abstract fun products(): SavedProductDao
}

data class BookmarkedProduct(
    val product: Product, val favorite: Boolean, val viewed: Boolean, val comparison: Boolean,
)

enum class SavedAction { VIEW, FAVORITE, COMPARE }

@Singleton
class SavedCatalog @Inject constructor(private val db: KitaiDatabase, private val gson: Gson) {
    private val dao = db.products()

    val products = dao.observe().map { rows ->
        rows.map { row ->
            val product = decodeSnapshot(row) ?: Product(
                    source = row.source, id = row.productId, url = row.url,
                    title = "Сохранённая ссылка", observedAt = "", titleRu = null,
                    imageUrl = null, priceMin = null, priceMax = null, priceRub = null,
                    currency = null, fxDate = null, seller = null, moq = null, retainUntil = null,
                )
            BookmarkedProduct(product, row.favorite, row.viewedAt != null, row.comparison)
        }
    }

    suspend fun purgeExpired() = dao.purgeExpired(expirationKey(Instant.now()))

    suspend fun update(product: Product, action: SavedAction, enabled: Boolean = true, preserveSnapshot: Boolean = false) {
        db.withTransaction {
            val stored = dao.find(product.source, product.id) ?: SavedProduct(
                source = product.source, productId = product.id, url = product.url,
                snapshot = null, expiresAt = null,
            )
            val expiry = product.retainUntil?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
            val snapshot = decodeSnapshot(stored)
            val keepSnapshot = (preserveSnapshot && snapshot != null) || !product.canReplace(snapshot)
            val current = if (keepSnapshot) stored else stored.copy(
                url = product.url,
                snapshot = when {
                    product.retainUntil != null && expiry == null -> null
                    expiry != null && expiry <= Instant.now() -> null
                    else -> gson.toJson(product)
                },
                expiresAt = expiry?.let(::expirationKey),
            )
            val changed = when (action) {
                SavedAction.VIEW -> current.copy(viewedAt = System.currentTimeMillis())
                SavedAction.FAVORITE -> current.copy(favorite = enabled)
                SavedAction.COMPARE -> current.copy(comparison = enabled)
            }
            dao.save(changed)
            dao.deleteUnused()
        }
    }

    suspend fun clearHistory() = db.withTransaction {
        dao.clearHistory()
        dao.deleteUnused()
    }

    private fun decodeSnapshot(row: SavedProduct): Product? = row.snapshot
        ?.takeIf { row.expiresAt == null || Instant.parse(row.expiresAt) > Instant.now() }
        ?.let { runCatching { gson.fromJson(it, Product::class.java) }.getOrNull() }

    // Fixed precision keeps UTC timestamps comparable in SQLite.
    private fun expirationKey(instant: Instant): String =
        java.time.format.DateTimeFormatterBuilder().appendInstant(9).toFormatter().format(instant)
}

enum class ThemeMode(val title: String) {
    SYSTEM("Как в системе"), LIGHT("Светлая"), DARK("Тёмная"),
}

data class Settings(val theme: ThemeMode = ThemeMode.SYSTEM, val openRouterKey: String = "") {
    override fun toString(): String = "Settings(theme=$theme)"
}

@Singleton
class KitaiSettings @Inject constructor(private val preferences: SharedPreferences) {
    fun read(): Settings = Settings(
        theme = ThemeMode.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: ThemeMode.SYSTEM,
        openRouterKey = preferences.getString(KEY_PREFERENCE, null)?.let {
            runCatching { decrypt(it) }.getOrNull()
        }.orEmpty(),
    )

    fun update(settings: Settings) {
        val edit = preferences.edit().putString("theme", settings.theme.name)
            .remove("api").remove("encrypted_api_token")
        if (settings.openRouterKey.isEmpty()) edit.remove(KEY_PREFERENCE)
        else edit.putString(KEY_PREFERENCE, encrypt(settings.openRouterKey))
        check(edit.commit())
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "kitai-openrouter-key"
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(token: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(token.encodeToByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
    }
    private companion object {
        const val KEY_PREFERENCE = "encrypted_openrouter_key"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): KitaiDatabase =
        Room.databaseBuilder(context, KitaiDatabase::class.java, "kitai.db").build()

    @Provides @Singleton
    fun preferences(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("kitai", Context.MODE_PRIVATE)

    @Provides @Singleton
    fun gson(): Gson = Gson()
}
