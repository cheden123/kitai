package ru.kitai.app

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KitaiUpdateSourceTest {
    private val installed = KitaiReleaseVersion(0, 2, 1)
    private val sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    private val base = "https://github.com/cheden123/kitai/releases/download"

    @Test
    fun choosesHighestNumericVersionIncludingPrereleasesAndSkipsUnrelatedAssets() {
        val selected = selectKitaiRelease(
            JsonParser.parseString(
                """
                [
                  ${release("0.4.0", draft = true)},
                  ${release("0.2.9")},
                  ${release("0.2.1")},
                  ${release("0.2.10", prerelease = true)},
                  ${release("0.2.11", assetName = "Kitai-0.2.11-release.apk")},
                  {"tag_name":"ucheba-v1.0.0","draft":false,"assets":[]},
                  {"tag_name":"v5.0.0","draft":false,"assets":{}}
                ]
                """.trimIndent(),
            ).asJsonArray,
            installed,
        )

        assertEquals(KitaiReleaseVersion(0, 2, 10), selected?.version)
        assertTrue(selected?.prerelease == true)
        assertEquals("$base/v0.2.10/Kitai-0.2.10-debug.apk", selected?.apkUrl.toString())
        assertEquals(sha256, selected?.sha256)
    }

    @Test
    fun skipsCurrentAndOlderVersions() {
        val releases = JsonParser.parseString("[${release("0.2.0")}, ${release("0.2.1")}]").asJsonArray
        assertNull(selectKitaiRelease(releases, installed))
        assertNull(KitaiReleaseVersion.parse("0.2.01"))
        assertNull(KitaiReleaseVersion.parse("2147483648.2.1"))
    }

    @Test
    fun rejectsSelectedAssetWithoutTrustedMetadata() {
        val invalid = listOf(
            release("0.2.3", digest = "null"),
            release("0.2.3", digest = "\"sha256:123456\""),
            release("0.2.3", size = 0),
            release("0.2.3", size = 200L * 1024 * 1024),
            release("0.2.3", state = "starter"),
            release("0.2.3", url = "https://example.com/Kitai-0.2.3-debug.apk"),
            release("0.2.3", url = "https://github.com/another/kitai/releases/download/v0.2.3/Kitai-0.2.3-debug.apk"),
        )
        invalid.forEach { candidate ->
            val releases = JsonParser.parseString("[${release("0.2.2")}, $candidate]").asJsonArray
            assertThrows(KitaiUpdateException::class.java) { selectKitaiRelease(releases, installed) }
        }
    }

    @Test
    fun permitsOnlyHttpsOnKnownGitHubAssetHosts() {
        val start = "$base/v0.2.2/Kitai-0.2.2-debug.apk".toHttpUrl()
        val asset = "https://release-assets.githubusercontent.com/github-production-release-asset/123?token=test"
        assertEquals(asset, kitaiAssetRedirect(start, asset).toString())
        listOf(
            "http://release-assets.githubusercontent.com/file",
            "https://release-assets.githubusercontent.com:8443/file",
            "https://release-assets.githubusercontent.com.example.com/file",
            "https://release-assets.githubusercontent.com@example.com/file",
            "https://anonymous@release-assets.githubusercontent.com/file",
            "https://github.com/another/project/releases/download/v0.2.2/file.apk",
            "//example.com/file",
            "/other",
            null,
        ).forEach { assertNull(kitaiAssetRedirect(start, it)) }
    }

    @Test
    fun verifiesEveryByteAndExactSize() {
        val bytes = "test".toByteArray()
        val output = ByteArrayOutputStream()
        runBlocking { copyVerifiedKitaiApk(bytes.inputStream(), output, 4, sha256) }
        assertArrayEquals(bytes, output.toByteArray())

        assertThrows(KitaiUpdateException::class.java) {
            runBlocking { copyVerifiedKitaiApk(bytes.inputStream(), ByteArrayOutputStream(), 3, sha256) }
        }
        assertThrows(KitaiUpdateException::class.java) {
            runBlocking { copyVerifiedKitaiApk(bytes.inputStream(), ByteArrayOutputStream(), 5, sha256) }
        }
        assertThrows(KitaiUpdateException::class.java) {
            runBlocking { copyVerifiedKitaiApk("tEst".byteInputStream(), ByteArrayOutputStream(), 4, sha256) }
        }
    }

    private fun release(
        version: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
        assetName: String = "Kitai-$version-debug.apk",
        url: String = "$base/v$version/$assetName",
        state: String = "uploaded",
        size: Long = 4,
        digest: String = "\"sha256:$sha256\"",
    ): String = """
        {
          "tag_name":"v$version",
          "draft":$draft,
          "prerelease":$prerelease,
          "assets":[{"name":"$assetName","state":"$state","size":$size,"digest":$digest,"browser_download_url":"$url"}]
        }
    """.trimIndent()
}
