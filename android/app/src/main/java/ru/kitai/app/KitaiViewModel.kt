package ru.kitai.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

enum class CatalogOrder(val title: String) {
    DEFAULT("По умолчанию"), RUBLES("По цене в рублях"), MINIMUM("По минимальной партии"),
}

data class CatalogState(
    val query: String = "",
    val submittedQuery: String = "",
    val originalQuery: String? = null,
    val sources: Set<Market> = Market.entries.toSet(),
    val outcomes: Map<String, SourceStatus> = emptyMap(),
    val products: List<Product> = emptyList(),
    val order: CatalogOrder = CatalogOrder.DEFAULT,
    val pricedOnly: Boolean = false,
    val cursor: String? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val queryTranslation: String? = null,
    val translationError: String? = null,
    val translating: Boolean = false,
)

data class DetailState(val product: Product, val loading: Boolean = false, val error: String? = null)

enum class Direction(val label: String, val from: String, val to: String) {
    RU_ZH("Русский → Китайский", "ru", "zh"),
    ZH_RU("Китайский → Русский", "zh", "ru"),
    EN_RU("Английский → Русский", "en", "ru"),
}

data class TranslationState(
    val loading: Boolean = false,
    val text: String? = null,
    val image: OcrResult? = null,
    val error: String? = null,
)

data class UploadImage(val bytes: ByteArray, val mime: String, val fileName: String)

@HiltViewModel
class KitaiViewModel @Inject constructor(
    private val network: KitaiNetwork,
    private val repository: SavedCatalog,
    private val preferences: KitaiSettings,
) : ViewModel() {
    private val settingsFlow = MutableStateFlow(preferences.read())
    val settings = settingsFlow.asStateFlow()
    private val catalogFlow = MutableStateFlow(CatalogState())
    val catalog = catalogFlow.asStateFlow()
    private val savedFlow = MutableStateFlow<List<BookmarkedProduct>>(emptyList())
    val saved = savedFlow.asStateFlow()
    private val detailFlow = MutableStateFlow<DetailState?>(null)
    val detail = detailFlow.asStateFlow()
    private val translationFlow = MutableStateFlow(TranslationState())
    val translation = translationFlow.asStateFlow()
    private val uploadFlow = MutableStateFlow<UploadImage?>(null)
    val upload = uploadFlow.asStateFlow()
    private val noticeFlow = MutableStateFlow<String?>(null)
    val notice = noticeFlow.asStateFlow()

    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var translationJob: Job? = null
    private var queryTranslationJob: Job? = null

    init {
        viewModelScope.launch {
            repository.products.catch { noticeFlow.value = "Не удалось открыть сохранённые товары." }
                .collect { savedFlow.value = it }
        }
        viewModelScope.launch {
            while (true) {
                safeWork({ noticeFlow.value = "Не удалось обновить сохранённые товары." }) {
                    repository.purgeExpired()
                }
                delay(60_000)
            }
        }
        if (settings.value.address.isNotBlank()) submit()
    }

    fun editQuery(query: String) {
        queryTranslationJob?.cancel()
        catalogFlow.update {
            it.copy(query = query.take(200), queryTranslation = null, translationError = null, translating = false)
        }
    }

    fun toggleSource(market: Market) {
        val previous = catalog.value.sources
        val next = if (market in previous) previous - market else previous + market
        if (next.isEmpty()) return
        catalogFlow.update { it.copy(sources = next) }
        search(catalog.value.submittedQuery, catalog.value.originalQuery)
    }

    fun setFilters(order: CatalogOrder = catalog.value.order, pricedOnly: Boolean = catalog.value.pricedOnly) {
        catalogFlow.update { it.copy(order = order, pricedOnly = pricedOnly) }
    }

    fun submit() = search(catalog.value.query.trim(), null)

    fun retry() = search(catalog.value.submittedQuery, catalog.value.originalQuery)

    fun submitTranslation() {
        val state = catalog.value
        val translated = state.queryTranslation ?: return
        search(translated.take(200), state.query.trim())
    }

    fun loadMore() {
        val state = catalog.value
        if (state.loading || state.cursor == null) return
        search(state.submittedQuery, state.originalQuery, state.cursor)
    }

    private fun search(query: String, original: String?, cursor: String? = null) {
        searchJob?.cancel()
        val current = catalog.value
        val firstPage = cursor == null
        val connection = settings.value
        val address = connection.address
        catalogFlow.update {
            it.copy(
                submittedQuery = query, originalQuery = original, loading = address.isNotBlank(),
                products = if (firstPage) emptyList() else it.products,
                outcomes = if (firstPage) emptyMap() else it.outcomes,
                cursor = cursor, error = null, loaded = if (firstPage) false else it.loaded,
            )
        }
        if (address.isBlank()) return
        searchJob = viewModelScope.launch {
            safeWork({ error ->
                val invalidCursor = cursor != null && error is HttpException && error.code() == 422
                catalogFlow.update {
                    it.copy(
                        loading = false,
                        cursor = if (invalidCursor) null else it.cursor,
                        error = if (invalidCursor) "Страница устарела. Выполните поиск ещё раз." else failureMessage(error),
                    )
                }
            }) {
                val api = network.api(connection)
                val response = if (query.isEmpty()) {
                    SearchResult(emptyList(), api.sources().sources, null)
                } else {
                    api.search(query, current.sources.map { it.id }, cursor)
                }
                catalogFlow.update {
                    it.copy(
                        products = (it.products + response.items.filter(Product::valid)).distinctBy(Product::key),
                        outcomes = it.outcomes + response.sources.associateBy(SourceStatus::id),
                        loading = false, loaded = query.isNotEmpty(),
                        cursor = response.nextCursor?.takeUnless { next -> next == cursor },
                    )
                }
            }
        }
    }

    fun translateQuery() {
        val query = catalog.value.query.trim()
        if (query.isEmpty()) return
        queryTranslationJob?.cancel()
        catalogFlow.update { it.copy(translating = true, translationError = null, queryTranslation = null) }
        queryTranslationJob = viewModelScope.launch {
            safeWork({ error ->
                catalogFlow.update { it.copy(translating = false, translationError = failureMessage(error)) }
            }) {
                val result = network.api(settings.value).translate(TranslateRequest(query, "ru", "zh"))
                catalogFlow.update { it.copy(translating = false, queryTranslation = result.text) }
            }
        }
    }

    fun openProduct(product: Product) {
        detailJob?.cancel()
        val connection = settings.value
        val snapshot = saved.value.firstOrNull { it.product.key == product.key }?.product
        val displayed = if (snapshot?.isDetailed == true) snapshot else product
        detailFlow.value = DetailState(displayed, loading = connection.address.isNotBlank())
        mark(product, SavedAction.VIEW)
        if (connection.address.isBlank()) return
        detailJob = viewModelScope.launch {
            safeWork({ error ->
                detailFlow.value = DetailState(displayed, error = failureMessage(error))
            }) {
                val response = network.api(connection).detail(product.source, product.id).copy(isDetailed = true)
                check(response.valid() && response.key == product.key)
                detailFlow.value = DetailState(response)
                mark(response, SavedAction.VIEW)
            }
        }
    }

    fun closeProduct() {
        detailJob?.cancel()
        detailFlow.value = null
    }

    fun mark(product: Product, action: SavedAction, enabled: Boolean = true) {
        viewModelScope.launch {
            safeWork({ noticeFlow.value = "Не удалось сохранить изменение." }) {
                repository.update(product, action, enabled)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            safeWork({ noticeFlow.value = "Не удалось очистить историю." }) { repository.clearHistory() }
        }
    }

    fun dismissNotice() { noticeFlow.value = null }

    fun saveSettings(new: Settings) {
        viewModelScope.launch {
            safeWork({ noticeFlow.value = "Не удалось сохранить настройки." }) {
                val connectionChanged = new.address != settings.value.address || new.token != settings.value.token
                withContext(Dispatchers.IO) { preferences.update(new) }
                settingsFlow.value = new
                if (connectionChanged) {
                    closeProduct()
                    queryTranslationJob?.cancel()
                    catalogFlow.update { it.copy(queryTranslation = null, translationError = null, translating = false) }
                    resetTranslation()
                    submit()
                }
            }
        }
    }

    fun resetTranslation() {
        translationJob?.cancel()
        translationFlow.value = TranslationState()
    }

    fun setUpload(image: UploadImage?) {
        uploadFlow.value = image
        resetTranslation()
    }

    fun translate(text: String, direction: Direction) = startTranslation { api ->
        val result = api.translate(TranslateRequest(text, direction.from, direction.to))
        TranslationState(text = result.text)
    }

    fun recognize(upload: UploadImage, direction: Direction) = startTranslation { api ->
        val image = MultipartBody.Part.createFormData(
            "file", upload.fileName, upload.bytes.toRequestBody(upload.mime.toMediaType()),
        )
        val plainText = "text/plain".toMediaType()
        val result = api.ocr(image, direction.from.toRequestBody(plainText), direction.to.toRequestBody(plainText))
        TranslationState(image = result.validated())
    }

    private fun startTranslation(request: suspend (KitaiApi) -> TranslationState) {
        translationJob?.cancel()
        translationFlow.value = TranslationState(loading = true)
        translationJob = viewModelScope.launch {
            safeWork({ translationFlow.value = TranslationState(error = failureMessage(it)) }) {
                translationFlow.value = request(network.api(settings.value))
            }
        }
    }

    private suspend fun safeWork(onFailure: (Exception) -> Unit, action: suspend () -> Unit) {
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFailure(error)
        }
    }
}

private fun failureMessage(error: Exception): String = when (error) {
    is HttpException -> when (error.code()) {
        400, 422 -> "Сервер отклонил параметры запроса."
        401, 403 -> "Сервер отклонил доступ к операции."
        404 -> "Сервер не предоставил запрошенные данные."
        413 -> "Изображение превышает допустимый размер."
        415 -> "Формат изображения не поддерживается."
        429 -> "Слишком много запросов. Попробуйте позже."
        501, 503 -> "Функция сейчас недоступна на сервере."
        else -> "Сервер вернул ошибку (${error.code()})."
    }
    is IOException -> "Не удалось связаться с сервером. Проверьте подключение и адрес."
    is IllegalStateException -> "Проверьте настройки сервера и доступность данных."
    else -> "Не удалось обработать ответ сервера."
}
