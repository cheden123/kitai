package ru.kitai.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import retrofit2.HttpException

data class CatalogState(
    val query: String = "",
    val queryTranslation: String? = null,
    val useTranslation: Boolean = false,
    val translationError: String? = null,
    val translating: Boolean = false,
) {
    val searchQuery: String get() = if (useTranslation) queryTranslation ?: query else query
}

enum class Direction(val label: String, val from: String, val sourceName: String, val targetName: String) {
    RU_ZH("Русский → Китайский", "ru", "Russian", "Simplified Chinese"),
    ZH_RU("Китайский → Русский", "zh", "Chinese", "Russian"),
    EN_RU("Английский → Русский", "en", "English", "Russian"),
}

data class TranslationState(
    val loading: Boolean = false,
    val text: String? = null,
    val image: OcrResult? = null,
    val error: String? = null,
)

data class UploadImage(val bytes: ByteArray)

@HiltViewModel
class KitaiViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val openRouter: OpenRouter,
    private val repository: SavedCatalog,
    private val preferences: KitaiSettings,
) : ViewModel() {
    private val localOcr = LocalOcr(context)
    private val settingsFlow = MutableStateFlow(preferences.read())
    val settings = settingsFlow.asStateFlow()
    private val catalogFlow = MutableStateFlow(CatalogState())
    val catalog = catalogFlow.asStateFlow()
    private val savedFlow = MutableStateFlow<List<BookmarkedProduct>>(emptyList())
    val saved = savedFlow.asStateFlow()
    private val detailFlow = MutableStateFlow<Product?>(null)
    val detail = detailFlow.asStateFlow()
    private val translationFlow = MutableStateFlow(TranslationState())
    val translation = translationFlow.asStateFlow()
    private val uploadFlow = MutableStateFlow<UploadImage?>(null)
    val upload = uploadFlow.asStateFlow()
    private val noticeFlow = MutableStateFlow<String?>(null)
    val notice = noticeFlow.asStateFlow()

    private var translationJob: Job? = null
    private var queryTranslationJob: Job? = null
    private var settingsJob: Job? = null

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
    }

    fun editQuery(query: String) {
        queryTranslationJob?.cancel()
        catalogFlow.value = CatalogState(query = query.take(200))
    }

    fun toggleQueryTranslation() {
        catalogFlow.update { it.copy(useTranslation = it.queryTranslation != null && !it.useTranslation) }
    }

    fun translateQuery() {
        val query = catalog.value.query.trim()
        val key = settings.value.openRouterKey
        if (query.isEmpty() || key.isEmpty()) return
        queryTranslationJob?.cancel()
        catalogFlow.update { it.copy(translating = true, translationError = null) }
        queryTranslationJob = viewModelScope.launch {
            safeWork({ error ->
                catalogFlow.update { it.copy(translating = false, translationError = translationFailure(error)) }
            }) {
                val result = openRouter.translate(key, query, Direction.RU_ZH)
                catalogFlow.update { it.copy(translating = false, queryTranslation = result, useTranslation = true) }
            }
        }
    }

    fun openProduct(product: Product) {
        detailFlow.value = product
        mark(product, SavedAction.VIEW)
    }

    fun closeProduct() {
        detailFlow.value = null
    }

    fun recordMarketplaceVisit(link: MarketplaceProductLink, title: String) {
        viewModelScope.launch {
            safeWork({ noticeFlow.value = "Не удалось сохранить просмотр." }) {
                repository.update(link.product(title), SavedAction.VIEW, preserveSnapshot = true)
            }
        }
    }

    fun saveMarketplaceProduct(product: Product, onSaved: (Boolean) -> Unit) {
        viewModelScope.launch {
            safeWork({ onSaved(false) }) {
                repository.update(product, SavedAction.FAVORITE)
                noticeFlow.value = "Товар сохранён в избранном."
                onSaved(true)
            }
        }
    }

    fun mark(product: Product, action: SavedAction, enabled: Boolean = true) {
        viewModelScope.launch {
            safeWork({ noticeFlow.value = "Не удалось сохранить изменение." }) {
                repository.update(product, action, enabled, preserveSnapshot = true)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            safeWork({ noticeFlow.value = "Не удалось очистить историю." }) { repository.clearHistory() }
        }
    }

    fun dismissNotice() {
        noticeFlow.value = null
    }

    fun saveSettings(new: Settings, onSaved: () -> Unit) {
        if (settingsJob?.isActive == true) return
        settingsJob = viewModelScope.launch {
            safeWork({ noticeFlow.value = "Не удалось сохранить настройки." }) {
                val keyChanged = new.openRouterKey != settings.value.openRouterKey
                withContext(Dispatchers.IO) { preferences.update(new) }
                settingsFlow.value = new
                if (keyChanged) {
                    editQuery(catalog.value.query)
                    resetTranslation()
                }
                onSaved()
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

    fun translate(text: String, direction: Direction) {
        val key = settings.value.openRouterKey
        if (key.isEmpty()) return
        launchTranslation {
            translationFlow.value = TranslationState(text = openRouter.translate(key, text, direction))
        }
    }

    fun recognize(upload: UploadImage, direction: Direction) {
        val key = settings.value.openRouterKey
        launchTranslation {
            val image = try {
                localOcr.recognize(upload.bytes, direction.from)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                translationFlow.value = TranslationState(error = "Не удалось распознать изображение.")
                return@launchTranslation
            }
            val translate = key.isNotEmpty() && image.blocks.isNotEmpty()
            translationFlow.value = TranslationState(image = image, loading = translate)
            if (translate) {
                val blocks = openRouter.translateBlocks(key, image.blocks, direction)
                translationFlow.value = TranslationState(image = image.copy(blocks = blocks))
            }
        }
    }

    private fun launchTranslation(request: suspend () -> Unit) {
        translationJob?.cancel()
        translationFlow.value = TranslationState(loading = true)
        translationJob = viewModelScope.launch {
            safeWork({ error ->
                translationFlow.update { it.copy(loading = false, error = translationFailure(error)) }
            }, request)
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

private fun translationFailure(error: Exception): String = when (error) {
    is HttpException -> when (error.code()) {
        401, 403 -> "OpenRouter отклонил ключ. Проверьте его в настройках."
        402 -> "OpenRouter отклонил запрос из-за ограничений аккаунта."
        404, 502, 503 -> "Бесплатные модели OpenRouter сейчас недоступны. Попробуйте позже."
        429 -> "Достигнут лимит бесплатных запросов OpenRouter. Попробуйте позже."
        else -> "OpenRouter вернул ошибку (${error.code()})."
    }
    is IncompleteTranslation -> "Модель вернула неполный перевод. Попробуйте ещё раз."
    is IOException -> "Не удалось связаться с OpenRouter. Проверьте подключение к интернету."
    else -> "Не удалось обработать перевод OpenRouter."
}
