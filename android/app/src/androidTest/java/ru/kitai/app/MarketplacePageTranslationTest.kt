package ru.kitai.app

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MarketplacePageTranslationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @SuppressLint("SetJavaScriptEnabled")
    @Test
    fun translatesVisiblePublicTextAndRestoresOriginalWithoutInsertingMarkup() {
        val address = "https://item.taobao.com/item.htm?id=42"
        val token = "test-page-token"
        val loaded = CountDownLatch(1)
        lateinit var browser: WebView
        instrumentation.runOnMainSync {
            browser = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, 1080, 1600)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        loaded.countDown()
                    }
                }
                loadDataWithBaseURL(address, """
                    <!doctype html><html><head><meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    </head><body><main>
                    <h1 id="name">Wireless headphones</h1>
                    <p>Noise cancellation</p>
                    <p style="display:none">Hidden text</p>
                    <form><label>Private form label<input type="password" value="secret"></label></form>
                    </main></body></html>
                """.trimIndent(), "text/html", "UTF-8", null)
            }
        }
        try {
            assertTrue("Fixture page did not load", loaded.await(10, TimeUnit.SECONDS))
            val source = instrumentation.targetContext.assets
                .open("marketplace-page-translation.js").bufferedReader().use { it.readText() }
            val capture = JSONObject(evaluate(browser, capturePageTranslationScript(source, address, token)))
            val texts = capture.getJSONArray("texts")
            val originals = (0 until texts.length()).map(texts::getString)
            assertTrue(originals.contains("Wireless headphones"))
            assertFalse(originals.contains("Private form label"))
            assertFalse(originals.contains("Hidden text"))
            val translations = originals.map { text ->
                if (text == "Wireless headphones") "<b>Беспроводные наушники</b>" else "Шумоподавление"
            }
            assertEquals("0", evaluate(browser, applyPageTranslationScript(address, "different-token", translations)))
            assertTrue(evaluate(browser, applyPageTranslationScript(address, token, translations)).toInt() > 0)
            val visibleText = evaluate(browser, "document.querySelector('#name').textContent")
            assertEquals("<b>Беспроводные наушники</b>", JSONTokener(visibleText).nextValue())
            assertEquals("true", evaluate(browser, "document.querySelector('#name b') === null"))
            assertTrue(evaluate(browser, togglePageTranslationScript(address, false)).toInt() > 0)
            val restored = evaluate(browser, "document.querySelector('#name').textContent")
            assertEquals("Wireless headphones", JSONTokener(restored).nextValue())
        } finally {
            instrumentation.runOnMainSync { browser.destroy() }
        }
    }

    private fun evaluate(browser: WebView, script: String): String {
        val finished = CountDownLatch(1)
        var result: String? = null
        instrumentation.runOnMainSync {
            browser.evaluateJavascript(script) {
                result = it
                finished.countDown()
            }
        }
        assertTrue("WebView script did not finish", finished.await(10, TimeUnit.SECONDS))
        return checkNotNull(result)
    }
}
