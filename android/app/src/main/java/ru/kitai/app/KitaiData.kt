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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query as ApiQuery

interface KitaiApi {
    @GET("api/v1/sources")
    suspend fun sources(): SourcesResult

    @GET("api/v1/search")
    suspend fun search(
        @ApiQuery("q") query: String,
        @ApiQuery("source") sources: List<String>,
        @ApiQuery("cursor") cursor: String?,
    ): SearchResult

    @GET("api/v1/products/{source}/{id}")
    suspend fun detail(@Path("source") source: String, @Path("id") id: String): Product

    @POST("api/v1/translate")
    suspend fun translate(@Body request: TranslateRequest): TranslatedText

    @Multipart
    @POST("api/v1/ocr")
    suspend fun ocr(
        @Part image: MultipartBody.Part,
        @Part("source_lang") source: RequestBody,
        @Part("target_lang") target: RequestBody,
    ): OcrResponse
}

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

    suspend fun update(product: Product, action: SavedAction, enabled: Boolean = true) {
        db.withTransaction {
            val stored = dao.find(product.source, product.id) ?: SavedProduct(
                source = product.source, productId = product.id, url = product.url,
                snapshot = null, expiresAt = null,
            )
            val expiry = product.retainUntil?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
            val current = if (!product.canReplace(decodeSnapshot(stored))) stored else stored.copy(
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

data class Settings(val address: String = "", val theme: ThemeMode = ThemeMode.SYSTEM, val token: String = "") {
    override fun toString(): String = "Settings(theme=$theme)"
}

@Singleton
class KitaiSettings @Inject constructor(private val preferences: SharedPreferences) {
    fun read(): Settings {
        val address = preferences.getString("api", "").orEmpty()
        return Settings(
            address,
            ThemeMode.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: ThemeMode.SYSTEM,
            preferences.getString("encrypted_api_token", null)?.let {
                runCatching { decrypt(address, it) }.getOrNull()
            }.orEmpty(),
        )
    }

    fun update(settings: Settings) {
        val edit = preferences.edit().putString("api", settings.address).putString("theme", settings.theme.name)
        if (settings.token.isEmpty()) edit.remove("encrypted_api_token")
        else edit.putString("encrypted_api_token", encrypt(settings.address, settings.token))
        edit.apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "kitai-api-token"
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

    private fun encrypt(address: String, token: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(address.encodeToByteArray())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(token.encodeToByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(address: String, value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(address.encodeToByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
    }
}

@Singleton
class KitaiNetwork @Inject constructor(private val gson: Gson) {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(85, TimeUnit.SECONDS)
        .build()
    private var addressInUse = ""
    private var tokenInUse = ""
    private var cachedApi: KitaiApi? = null

    @Synchronized
    fun api(settings: Settings): KitaiApi {
        if (addressInUse == settings.address && tokenInUse == settings.token) cachedApi?.let { return it }
        val trusted = apiAddress(settings.address, BuildConfig.DEBUG)
            ?: throw IllegalStateException("Сначала укажите адрес сервера в настройках.")
        val token = secureApiToken(trusted, settings.token)
        val authenticatedClient = if (token == null) client else client.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("$trusted/")
            .client(authenticatedClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(KitaiApi::class.java)
            .also {
                addressInUse = settings.address
                tokenInUse = settings.token
                cachedApi = it
            }
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
