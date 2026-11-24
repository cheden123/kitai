package ru.kitai.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarketplaceWebScreen(
    destination: WebDestination,
    canTranslate: Boolean,
    openTranslationSettings: () -> Unit,
    translatePage: suspend (List<String>) -> List<String>,
    onVisit: (MarketplaceProductLink, String) -> Unit,
    onAddProduct: (MarketplaceProductLink, String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val captureSource = remember(context) {
        lazy { context.assets.open("marketplace-page-translation.js").bufferedReader().use { it.readText() } }
    }
    var currentAddress by rememberSaveable(destination.url) { mutableStateOf(destination.url) }
    var pageTitle by remember(destination) { mutableStateOf("") }
    var loading by remember(destination) { mutableStateOf(true) }
    var error by remember(destination) { mutableStateOf<String?>(null) }
    var navigationNotice by remember(destination) { mutableStateOf<String?>(null) }
    var rendererGone by remember(destination) { mutableStateOf(false) }
    var browser by remember(destination) { mutableStateOf<WebView?>(null) }
    var pageGeneration by remember(destination) { mutableStateOf(0) }
    var translation by remember(destination) { mutableStateOf(PageTranslationUi()) }
    var translationJob by remember(destination) { mutableStateOf<Job?>(null) }
    if (!destination.market.accepts(destination.url)) {
        InfoCard("Ссылка недоступна", "Откройте проверенную ссылку площадки.")
        return
    }
    fun resetPageTranslation() {
        translationJob?.cancel()
        translationJob = null
        pageGeneration++
        translation = PageTranslationUi()
    }

    fun changeTranslation(showTranslation: Boolean? = null) {
        val view = browser ?: return
        val address = currentAddress
        val generation = pageGeneration
        fun isCurrent() = browser === view && pageGeneration == generation && currentAddress == address &&
            view.url == address && !loading && !rendererGone && error == null
        if (translation.busy || !isCurrent() || !isPublicMarketplacePage(destination.market, address)) return
        if (!canTranslate) {
            openTranslationSettings()
            return
        }

        translation = translation.copy(busy = true, message = null)
        translationJob = scope.launch {
            try {
                if (showTranslation != null) {
                    val count = view.runPageTranslationScript(togglePageTranslationScript(address, showTranslation)).toIntOrNull()
                    if (!isCurrent()) return@launch
                    translation = if (count != null && count > 0) {
                        translation.copy(showingTranslation = showTranslation)
                    } else {
                        PageTranslationUi(message = "Текст страницы обновился. Переведите его ещё раз.")
                    }
                    return@launch
                }

                val source = withContext(Dispatchers.IO) { captureSource.value }
                if (!isCurrent()) return@launch
                val token = UUID.randomUUID().toString()
                val capture = parsePageTranslationCapture(
                    view.runPageTranslationScript(capturePageTranslationScript(source, address, token)),
                )
                if (!isCurrent()) return@launch
                if (capture.texts.isEmpty()) {
                    translation = translation.copy(message = "В видимой области нет нового текста. Прокрутите страницу.")
                    return@launch
                }
                val results = translatePage(capture.texts)
                if (!isCurrent()) return@launch
                val count = view.runPageTranslationScript(applyPageTranslationScript(address, token, results)).toIntOrNull()
                if (!isCurrent()) return@launch
                if (count == null || count < 1) throw PageTranslationUnavailable()

                val matchingTitle = capture.texts.indexOf(pageTitle)
                translation = translation.copy(
                    available = true,
                    showingTranslation = true,
                    title = results.getOrNull(matchingTitle)?.take(200) ?: translation.title,
                    message = if (capture.hasMore) "Нажмите «Перевести видимый текст», чтобы продолжить."
                    else "Перевод показан. Прокрутите страницу, чтобы продолжить.",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isCurrent()) translation = translation.copy(message = translationFailure(failure))
            } finally {
                if (browser === view && pageGeneration == generation) translation = translation.copy(busy = false)
            }
        }
    }

    val productLink = marketplaceProductLink(currentAddress)
    val displayTitle = pageTitle.ifBlank { "Товар ${productLink?.id.orEmpty()}" }
    LaunchedEffect(productLink, loading, error) {
        if (productLink != null && !loading && error == null) onVisit(productLink, displayTitle)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                secureUri(currentAddress)?.host.orEmpty(),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            if (productLink != null && !loading && error == null) {
                TextButton(onClick = { onAddProduct(productLink, translation.title ?: displayTitle) }) { Text("Добавить") }
            }
            TextButton(onClick = {
                if (destination.market.accepts(currentAddress)) {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(currentAddress)).addCategory(Intent.CATEGORY_BROWSABLE)
                        )
                    } catch (_: ActivityNotFoundException) {
                        error = "На устройстве нет браузера для этой ссылки."
                    }
                }
            }) { Text("В браузере") }
        }
        HorizontalDivider()
        if (rendererGone) {
            Column(Modifier.padding(14.dp)) {
                InfoCard("Просмотр страницы прерван", "Откройте страницу заново или перейдите в браузер.") {
                    TextButton(onClick = { loading = true; error = null; rendererGone = false }) { Text("Открыть заново") }
                }
            }
            return@Column
        }
        if (!loading && error == null && isPublicMarketplacePage(destination.market, currentAddress)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                if (!translation.available || translation.showingTranslation) {
                    TextButton(onClick = { changeTranslation() }, enabled = !translation.busy) {
                        Text(when {
                            !canTranslate -> "Настроить перевод"
                            translation.available -> "Перевести видимый текст"
                            else -> "Перевести страницу"
                        })
                    }
                }
                if (translation.available) {
                    TextButton(onClick = { changeTranslation(!translation.showingTranslation) }, enabled = !translation.busy) {
                        Text(if (translation.showingTranslation) "Оригинал" else "Показать перевод")
                    }
                }
            }
            val message = if (translation.busy) "Переводим текст страницы…" else translation.message
            message?.let {
                Text(it, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
        if (loading || translation.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.error) }
        navigationNotice?.let {
            Text(it, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
        }
        AndroidView(
            factory = { activity ->
                FrameLayout(activity).apply {
                    val frame = this
                    val webView = WebView(activity).apply {
                        settings.apply {
                            allowFileAccess = false
                            allowContentAccess = false
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            safeBrowsingEnabled = true
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            private fun allows(request: WebResourceRequest): Boolean {
                                val address = request.url.toString()
                                return if (request.isForMainFrame) {
                                    request.method == "GET" && destination.market.accepts(address)
                                } else secureUri(address) != null
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val allowed = allows(request)
                                if (!allowed && request.isForMainFrame) {
                                    navigationNotice = "Переход заблокирован."
                                    if (loading) {
                                        error = navigationNotice
                                        navigationNotice = null
                                        loading = false
                                    }
                                }
                                return !allowed
                            }

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                if (allows(request)) null
                                else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                resetPageTranslation()
                                if (!destination.market.accepts(url)) {
                                    view.stopLoading()
                                    error = "Переход на другой сайт заблокирован."
                                    loading = false
                                    return
                                }
                                currentAddress = url
                                pageTitle = ""
                                loading = true
                                error = null
                                navigationNotice = null
                            }

                            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                                if (url != currentAddress && destination.market.accepts(url)) {
                                    resetPageTranslation()
                                    currentAddress = url
                                    pageTitle = view.title.orEmpty().trim().take(200)
                                    navigationNotice = null
                                    view.evaluateJavascript(restoreAfterMarketplaceNavigationScript(url), null)
                                }
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                if (url == currentAddress) {
                                    pageTitle = view.title.orEmpty().trim().take(200)
                                    loading = false
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                response: WebResourceResponse,
                            ) {
                                if (request.isForMainFrame) {
                                    error = "Площадка не открыла страницу. Попробуйте в браузере."
                                    loading = false
                                }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                failure: WebResourceError,
                            ) {
                                if (request.isForMainFrame) {
                                    error = "Страница площадки недоступна."
                                    loading = false
                                }
                            }

                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                resetPageTranslation()
                                browser = null
                                frame.removeView(view)
                                view.destroy()
                                loading = false
                                rendererGone = true
                                return true
                            }
                        }
                    }
                    browser = webView
                    addView(webView, MATCH_PARENT, MATCH_PARENT)
                    webView.loadUrl(currentAddress.takeIf(destination.market::accepts) ?: destination.url)
                }
            },
            onRelease = { frame ->
                (frame.getChildAt(0) as? WebView)?.let { view ->
                    if (browser === view) {
                        resetPageTranslation()
                        browser = null
                    }
                    frame.removeView(view)
                    view.stopLoading()
                    view.destroy()
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

private data class PageTranslationUi(
    val busy: Boolean = false,
    val available: Boolean = false,
    val showingTranslation: Boolean = false,
    val title: String? = null,
    val message: String? = null,
)
