package ru.kitai.app

import com.google.gson.annotations.SerializedName
import java.net.URI
import java.text.NumberFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Market(val id: String, val title: String, val homepage: String, private vararg val domains: String) {
    TAOBAO("taobao", "Taobao", "https://www.taobao.com/", "taobao.com", "tmall.com"),
    ALI1688("1688", "1688", "https://www.1688.com/", "1688.com"),
    ALIBABA("alibaba", "Alibaba.com", "https://www.alibaba.com/", "alibaba.com");

    fun accepts(address: String): Boolean {
        val uri = secureUri(address) ?: return false
        val host = uri.host.lowercase(Locale.ROOT)
        return domains.any { domain -> host == domain || host.endsWith(".$domain") }
    }

    companion object {
        fun byId(id: String): Market? = entries.firstOrNull { it.id == id }
    }
}

fun secureUri(value: String): URI? {
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    return uri.takeIf {
        it.scheme.equals("https", true) && it.host != null && it.rawUserInfo == null &&
            (it.port == -1 || it.port == 443)
    }
}

fun apiAddress(value: String, allowLocalHttp: Boolean): String? {
    val trimmed = value.trim().trimEnd('/')
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    val encrypted = uri.scheme.equals("https", true)
    val localHttp = allowLocalHttp && uri.scheme.equals("http", true) &&
        host in setOf("localhost", "127.0.0.1", "10.0.2.2")
    return trimmed.takeIf {
        (encrypted || localHttp) && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)
    }
}

fun secureApiToken(address: String, token: String): String? {
    val https = URI.create(apiAddress(address, false) ?: return null).scheme.equals("https", true)
    return token.takeIf { https && it.length in 1..512 && it.all { char -> char in '!'..'~' } }
}

data class Product(
    val source: String,
    val id: String,
    val title: String,
    val url: String,
    @SerializedName("observed_at") val observedAt: String,
    @SerializedName("title_ru") val titleRu: String?,
    @SerializedName("image_url") val imageUrl: String?,
    @SerializedName("price_min") val priceMin: String?,
    @SerializedName("price_max") val priceMax: String?,
    val currency: String?,
    @SerializedName("price_rub") val priceRub: String?,
    @SerializedName("fx_date") val fxDate: String?,
    val seller: String?,
    val moq: String?,
    @SerializedName("retain_until") val retainUntil: String?,
    @SerializedName("_kitai_detailed") val isDetailed: Boolean = false,
) {
    val key: String get() = "$source:$id"
    val market: Market? get() = Market.byId(source)
    val displayTitle: String get() = titleRu?.takeIf { it.isNotBlank() } ?: title
    val isLinkOnly: Boolean get() = observedAt.isEmpty()

    fun canReplace(snapshot: Product?): Boolean = !isLinkOnly && (snapshot?.isDetailed != true || isDetailed)

    fun originalPrice(): String {
        val low = priceMin?.toBigDecimalOrNull() ?: return "Цена по запросу"
        val high = priceMax?.toBigDecimalOrNull()
        val amount = if (high != null && high > low) {
            "${formatNumber(low.toPlainString())}–${formatNumber(high.toPlainString())}"
        } else {
            formatNumber(low.toPlainString())
        }
        return listOfNotNull(amount, currency?.takeIf(String::isNotBlank)).joinToString(" ")
    }

    fun rubles(): String? {
        val rubles = priceRub?.toBigDecimalOrNull() ?: return null
        val low = priceMin?.toBigDecimalOrNull()
        val high = priceMax?.toBigDecimalOrNull()
        val minimum = formatNumber(rubles.toPlainString())
        return if (low != null && high != null && high > low) "от ≈ $minimum ₽" else "≈ $minimum ₽"
    }

    fun valid(): Boolean = !source.isNullOrBlank() && !url.isNullOrBlank() && market?.accepts(url) == true &&
        !id.isNullOrBlank() && !title.isNullOrBlank() && runCatching { OffsetDateTime.parse(observedAt) }.isSuccess
}

data class SourceStatus(val id: String, val status: String) {
    val description: String get() = when (status) {
        "available" -> "Подключён"
        "configured" -> "Доступ ещё не проверен"
        "not_configured" -> "Подключение не настроено"
        "unsupported" -> "Операция недоступна"
        "forbidden" -> "Площадка отклонила доступ"
        "rate_limited" -> "Площадка ограничила запросы"
        "unavailable" -> "Временно недоступен"
        else -> "Статус неизвестен"
    }
}

data class SearchResult(
    val items: List<Product>,
    val sources: List<SourceStatus>,
    @SerializedName("next_cursor") val nextCursor: String?,
)
data class SourcesResult(val sources: List<SourceStatus>)

data class TranslateRequest(
    val text: String,
    @SerializedName("source_lang") val source: String,
    @SerializedName("target_lang") val target: String,
)

data class TranslatedText(val text: String, val engine: String)

data class OcrBounds(val x: Int, val y: Int, val width: Int, val height: Int)
data class OcrBlock(val text: String, val translation: String?, val box: OcrBounds?)
data class OcrResult(val width: Int, val height: Int, val blocks: List<OcrBlock>)

data class OcrResponse(val width: Int, val height: Int, val blocks: List<OcrBlockResponse?>?) {
    fun validated(): OcrResult {
        require(width > 0 && height > 0 && width.toLong() * height <= 12_000_000)
        val normalized = requireNotNull(blocks).take(100).mapNotNull { block ->
            if (block == null) return@mapNotNull null
            val text = block.text?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            OcrBlock(text, block.translation?.takeIf(String::isNotBlank), block.bounds(width, height))
        }
        return OcrResult(width, height, normalized)
    }
}

data class OcrBlockResponse(val text: String?, val translation: String?, val box: List<Int?>?) {
    fun bounds(imageWidth: Int, imageHeight: Int): OcrBounds? {
        val coordinates = box ?: return null
        if (coordinates.size != 4 || coordinates.any { it == null }) return null
        val (x, y, width, height) = coordinates.filterNotNull()
        if (x < 0 || y < 0 || width <= 0 || height <= 0 ||
            x.toLong() + width > imageWidth || y.toLong() + height > imageHeight
        ) {
            return null
        }
        return OcrBounds(x, y, width, height)
    }
}

private val russian = Locale.forLanguageTag("ru-RU")
private val observationFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm xxx", russian)

fun formatNumber(value: String): String {
    val number = value.toBigDecimalOrNull() ?: return value
    return NumberFormat.getNumberInstance(russian).apply { maximumFractionDigits = 2 }.format(number)
}

fun formatObservation(value: String): String =
    runCatching { OffsetDateTime.parse(value).format(observationFormat) }.getOrDefault(value)

fun formatDay(value: String): String =
    runCatching {
        java.time.LocalDate.parse(value).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }.getOrDefault(value)
