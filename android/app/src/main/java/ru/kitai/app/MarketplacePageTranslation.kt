package ru.kitai.app

import android.webkit.WebView
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private val publicPageAnchor = Regex("[A-Za-z0-9_.:-]{0,100}")
private val mobileAlibabaProduct = Regex("/product/[0-9]{1,20}/[^/]+\\.html/?")
private val alibabaCategory = Regex("/g/[A-Za-z0-9_-]+\\.html/?")
private val alibabaCatalogHosts = setOf("alibaba.com", "www.alibaba.com", "m.alibaba.com", "russian.alibaba.com")
private val searchParameterNames = setOf("q", "query", "keyword", "keywords", "searchtext")
private const val maxPageFragments = 36
private const val maxPageCharacters = 4_500
private const val maxFragmentCharacters = 1_500
private val pageJson = Gson()

internal fun isPublicMarketplacePage(market: Market, address: String): Boolean {
    val uri = secureUri(address) ?: return false
    if (!publicPageAnchor.matches(uri.rawFragment.orEmpty())) return false
    val host = uri.host.lowercase(Locale.ROOT)
    val path = uri.rawPath.orEmpty().ifEmpty { "/" }
    val product = marketplaceProductLink(address)?.market == market
    return when (market) {
        Market.TAOBAO -> when (host) {
            "taobao.com", "www.taobao.com", "tmall.com", "www.tmall.com" -> path == "/"
            "m.taobao.com" -> path == "/" || product
            "s.taobao.com" -> path == "/search"
            "list.tmall.com" -> path == "/search_product.htm"
            "item.taobao.com", "h5.m.taobao.com", "detail.tmall.com", "detail.m.tmall.com" -> product
            else -> false
        }
        Market.ALI1688 -> when (host) {
            "1688.com", "www.1688.com" -> path == "/"
            "m.1688.com" -> path == "/" || product
            "s.1688.com" -> path == "/selloffer/offer_search.htm"
            "detail.1688.com" -> product
            else -> false
        }
        Market.ALIBABA -> host in alibabaCatalogHosts &&
            (path == "/" || path == "/trade/search" || product || alibabaCategory.matches(path) ||
                (host == "m.alibaba.com" && mobileAlibabaProduct.matches(path)))
    }
}

internal fun marketplaceSearchTerms(address: String): List<String> = secureUri(address)?.rawQuery.orEmpty()
    .splitToSequence('&')
    .mapNotNull { parameter ->
        val name = runCatching { URLDecoder.decode(parameter.substringBefore('='), "UTF-8").lowercase(Locale.ROOT) }
            .getOrNull()
        if (name !in searchParameterNames) return@mapNotNull null
        runCatching { URLDecoder.decode(parameter.substringAfter('=', ""), "UTF-8").trim() }
            .getOrNull()?.takeIf(String::isNotEmpty)
    }
    .distinct()
    .toList()

internal fun capturePageTranslationScript(
    source: String,
    address: String,
    token: String,
    excludedQueries: List<String> = marketplaceSearchTerms(address),
): String = "($source)(${pageJson.toJson(address)}, ${pageJson.toJson(token)}, ${pageJson.toJson(excludedQueries)})"

internal fun applyPageTranslationScript(address: String, token: String, translations: List<String>): String =
    "window.__kitaiPageTranslation ? window.__kitaiPageTranslation.apply(" +
        "${pageJson.toJson(address)}, ${pageJson.toJson(token)}, ${pageJson.toJson(translations)}) : 0"

internal fun togglePageTranslationScript(address: String, showTranslation: Boolean): String =
    "window.__kitaiPageTranslation ? window.__kitaiPageTranslation.toggle(" +
        "${pageJson.toJson(address)}, $showTranslation) : 0"

internal fun restoreAfterMarketplaceNavigationScript(address: String): String = """
    (() => {
        const previous = window.__kitaiPageTranslation;
        if (location.href === ${pageJson.toJson(address)} && previous && previous.address !== location.href) {
            previous.restore();
            delete window.__kitaiPageTranslation;
        }
    })()
""".trimIndent()

internal data class PageTranslationCapture(val texts: List<String>, val hasMore: Boolean)

internal fun parsePageTranslationCapture(result: String): PageTranslationCapture {
    val capture = runCatching {
        val json = JsonParser.parseString(result).asJsonObject
        val texts = json.getAsJsonArray("texts")
        require(texts.size() <= maxPageFragments)
        val fragments = texts.map { element ->
            require(element.isJsonPrimitive && element.asJsonPrimitive.isString)
            element.asString.trim().also { require(it.length in 1..maxFragmentCharacters) }
        }
        require(fragments.sumOf(String::length) <= maxPageCharacters)
        val hasMore = json.getAsJsonPrimitive("hasMore")
        require(hasMore.isBoolean)
        PageTranslationCapture(fragments, hasMore.asBoolean)
    }.getOrNull()
    return capture ?: throw PageTranslationUnavailable()
}

internal suspend fun WebView.runPageTranslationScript(script: String): String = withTimeoutOrNull(7_500) {
    suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { result -> continuation.resume(result) }
    }
} ?: throw PageTranslationUnavailable()

internal class PageTranslationUnavailable : IllegalStateException()
