package ru.kitai.app

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

private const val RELEASES = "https://api.github.com/repos/cheden123/kitai/releases"
private const val DOWNLOADS = "https://github.com/cheden123/kitai/releases/download"
private const val PAGE_SIZE = 50
private const val MAX_PAGES = 10
private const val MAX_RELEASE_RESPONSE = 2 * 1024 * 1024L
private const val MAX_APK_SIZE = 150 * 1024 * 1024L
private val releaseVersionPattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
private val digestPattern = Regex("sha256:([0-9a-fA-F]{64})")
private val releaseAssetHosts = setOf(
    "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
    "github-releases.githubusercontent.com",
)

internal data class KitaiReleaseVersion(val major: Int, val minor: Int, val patch: Int) :
    Comparable<KitaiReleaseVersion> {
    override fun compareTo(other: KitaiReleaseVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(name: String): KitaiReleaseVersion? {
            val parts = releaseVersionPattern.matchEntire(name)?.groupValues ?: return null
            return KitaiReleaseVersion(
                parts[1].toIntOrNull() ?: return null,
                parts[2].toIntOrNull() ?: return null,
                parts[3].toIntOrNull() ?: return null,
            )
        }
    }
}

internal data class KitaiRelease(
    val version: KitaiReleaseVersion,
    val prerelease: Boolean,
    val apkUrl: HttpUrl,
    val size: Long,
    val sha256: String,
)

internal class KitaiUpdateException(message: String) : IOException(message)

internal fun selectKitaiRelease(
    releases: Iterable<JsonElement>,
    installed: KitaiReleaseVersion,
): KitaiRelease? {
    val candidate = releases.asSequence().mapNotNull { element ->
        val release = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        if (release.boolean("draft") != false) return@mapNotNull null
        val tag = release.string("tag_name") ?: return@mapNotNull null
        if (!tag.startsWith('v')) return@mapNotNull null
        val version = KitaiReleaseVersion.parse(tag.drop(1)) ?: return@mapNotNull null
        if (version <= installed) return@mapNotNull null
        val assetName = "Kitai-$version-debug.apk"
        val assets = release.get("assets")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.filter { it.isJsonObject && it.asJsonObject.string("name") == assetName }
            .orEmpty()
        if (assets.isEmpty()) return@mapNotNull null
        ReleaseCandidate(version, release.boolean("prerelease") == true, assets.map { it.asJsonObject })
    }.maxByOrNull { it.version } ?: return null

    val version = candidate.version
    val asset = candidate.assets.singleOrNull() ?: invalidAsset()
    val apkUrl = "$DOWNLOADS/v$version/Kitai-$version-debug.apk".toHttpUrl()
    if (asset.string("state") != "uploaded" || asset.string("browser_download_url") != apkUrl.toString()) {
        invalidAsset()
    }
    val size = asset.get("size")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asString?.toLongOrNull()?.takeIf { it in 1..MAX_APK_SIZE } ?: invalidAsset()
    val sha256 = asset.string("digest")?.let { digestPattern.matchEntire(it)?.groupValues?.get(1) }
        ?.lowercase(Locale.ROOT) ?: invalidAsset()
    return KitaiRelease(version, candidate.prerelease, apkUrl, size, sha256)
}

private data class ReleaseCandidate(
    val version: KitaiReleaseVersion,
    val prerelease: Boolean,
    val assets: List<JsonObject>,
)

private fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.boolean(name: String): Boolean? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

private fun invalidAsset(): Nothing =
    throw KitaiUpdateException("GitHub не подтвердил размер, адрес или контрольную сумму APK.")

internal fun kitaiAssetRedirect(from: HttpUrl, location: String?): HttpUrl? =
    location?.let { from.resolve(it) }?.takeIf {
        it.isHttps && it.port == 443 && it.host in releaseAssetHosts &&
            it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
    }

internal suspend fun copyVerifiedKitaiApk(
    input: InputStream,
    output: OutputStream,
    expectedSize: Long,
    expectedSha256: String,
) {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val context = currentCoroutineContext()
    var size = 0L
    while (true) {
        context.ensureActive()
        val count = input.read(buffer)
        if (count == -1) break
        size += count
        if (size > expectedSize) throw KitaiUpdateException("Размер APK не совпадает с указанным на GitHub.")
        digest.update(buffer, 0, count)
        output.write(buffer, 0, count)
    }
    if (size != expectedSize) throw KitaiUpdateException("Размер APK не совпадает с указанным на GitHub.")
    val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
    if (actualSha256 != expectedSha256) throw KitaiUpdateException("Контрольная сумма APK не совпадает с указанной на GitHub.")
}

internal class KitaiUpdateSource {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.MINUTES)
        .build()

    suspend fun findUpdate(installed: KitaiReleaseVersion): KitaiRelease? {
        val releases = mutableListOf<JsonElement>()
        for (page in 1..MAX_PAGES) {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder()
                .url("$RELEASES?per_page=$PAGE_SIZE&page=$page")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Kitai-Android")
                .build()
            val call = client.newCall(request)
            call.timeout().timeout(30, TimeUnit.SECONDS)
            val entries = call.execute().use { response ->
                if (response.code == 429 || (response.code == 403 && response.header("X-RateLimit-Remaining") == "0")) {
                    throw KitaiUpdateException("GitHub временно ограничил число запросов. Попробуйте позже.")
                }
                if (!response.isSuccessful) throw KitaiUpdateException("GitHub вернул ошибку HTTP ${response.code}.")
                val body = response.body ?: throw invalidResponse()
                val input = body.source()
                if (input.request(MAX_RELEASE_RESPONSE + 1)) throw invalidResponse()
                try {
                    JsonParser.parseString(input.readUtf8()).asJsonArray
                } catch (_: JsonParseException) {
                    throw invalidResponse()
                } catch (_: IllegalStateException) {
                    throw invalidResponse()
                }
            }
            releases.addAll(entries)
            if (entries.size() < PAGE_SIZE) return selectKitaiRelease(releases, installed)
        }
        throw KitaiUpdateException("Не удалось проверить все выпуски Kitai на GitHub.")
    }

    suspend fun download(release: KitaiRelease, output: OutputStream) {
        var url = release.apkUrl
        repeat(4) {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/octet-stream")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Kitai-Android")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    url = kitaiAssetRedirect(url, response.header("Location"))
                        ?: throw KitaiUpdateException("GitHub перенаправил загрузку на неизвестный адрес.")
                } else {
                    if (response.code != 200) {
                        throw KitaiUpdateException("Не удалось скачать APK. GitHub вернул ошибку HTTP ${response.code}.")
                    }
                    val body = response.body ?: throw invalidResponse()
                    if (body.contentLength() >= 0 && body.contentLength() != release.size) {
                        throw KitaiUpdateException("Размер APK не совпадает с указанным на GitHub.")
                    }
                    copyVerifiedKitaiApk(body.byteStream(), output, release.size, release.sha256)
                    return
                }
            }
        }
        throw KitaiUpdateException("Слишком много перенаправлений при загрузке APK.")
    }

    private fun invalidResponse() = KitaiUpdateException("Не удалось прочитать ответ GitHub.")
}
