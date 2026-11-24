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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarketplaceWebScreen(
    destination: WebDestination,
    onVisit: (MarketplaceProductLink, String) -> Unit,
    onAddProduct: (MarketplaceProductLink, String) -> Unit,
) {
    val context = LocalContext.current
    var currentAddress by rememberSaveable(destination.url) { mutableStateOf(destination.url) }
    var pageTitle by remember(destination) { mutableStateOf("") }
    var loading by remember(destination) { mutableStateOf(true) }
    var error by remember(destination) { mutableStateOf<String?>(null) }
    var rendererGone by remember(destination) { mutableStateOf(false) }
    if (!destination.market.accepts(destination.url)) {
        InfoCard("Ссылка недоступна", "Откройте проверенную ссылку площадки.")
        return
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
                TextButton(onClick = { onAddProduct(productLink, displayTitle) }) { Text("Добавить") }
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
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.error) }
        AndroidView(
            factory = { activity ->
                FrameLayout(activity).apply {
                    val frame = this
                    val browser = WebView(activity).apply {
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
                                    error = "Переход заблокирован."
                                    loading = false
                                }
                                return !allowed
                            }

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                                if (allows(request)) null
                                else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(byteArrayOf()))

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
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
                                frame.removeView(view)
                                view.destroy()
                                loading = false
                                rendererGone = true
                                return true
                            }
                        }
                    }
                    addView(browser, MATCH_PARENT, MATCH_PARENT)
                    browser.loadUrl(currentAddress.takeIf(destination.market::accepts) ?: destination.url)
                }
            },
            onRelease = { frame ->
                (frame.getChildAt(0) as? WebView)?.let { browser ->
                    frame.removeView(browser)
                    browser.stopLoading()
                    browser.destroy()
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}
