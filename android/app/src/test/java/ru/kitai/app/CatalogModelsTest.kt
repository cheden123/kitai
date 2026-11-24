package ru.kitai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogModelsTest {
    @Test
    fun marketplaceRequiresHttpsAndCorrectHostnameBoundary() {
        val market = Market.TAOBAO
        assertTrue(market.accepts("https://ITEM.TAOBAO.COM/item.htm?id=42"))
        assertTrue(market.accepts("https://taobao.com:443/"))
        assertTrue(market.accepts("https://detail.tmall.com/item.htm?id=42"))
        assertFalse(market.accepts("https://tmall.com.example.org/"))
        assertFalse(Market.ALIBABA.accepts("https://detail.tmall.com/item.htm?id=42"))
        assertFalse(market.accepts("https://faketaobao.com/"))
        assertFalse(market.accepts("https://taobao.com.example.org/"))
        assertFalse(market.accepts("https://taobao.com@evil.example/"))
        assertFalse(market.accepts("https://evil.example@taobao.com/"))
        assertFalse(market.accepts("https://taobao.com:8080/"))
        assertFalse(market.accepts("http://taobao.com/"))
        assertFalse(market.accepts("javascript:document.location"))
    }

    @Test
    fun apiAddressAndTokenStayWithinConfiguredTrustPolicy() {
        assertEquals("https://kitai.example:8443/kitai", apiAddress(" https://kitai.example:8443/kitai/ ", false))
        assertNull(apiAddress("https://token@kitai.example", true))
        assertNull(apiAddress("https://kitai.example/?token=private", true))
        assertNull(apiAddress("http://10.0.2.2:8000", false))
        assertEquals("http://10.0.2.2:8000", apiAddress("http://10.0.2.2:8000", true))
        assertNull(apiAddress("http://10.0.2.2.example.org", true))
        assertNull(secureApiToken("http://10.0.2.2:8000", "private-token"))
        assertNull(secureApiToken("https://kitai.example", "private\nheader"))
        assertEquals("private-token", secureApiToken("https://kitai.example", "private-token"))
    }

    @Test
    fun priceRangeKeepsOriginalBoundsAndShowsOnlyKnownRubleEstimate() {
        val product = Product(
            "taobao", "42", "台灯", "https://item.taobao.com/item.htm?id=42",
            "2026-11-24T03:00:00+08:00", null, null, "18.5", "20.5", "CNY", "222.00",
            "2026-11-24", null, null, null,
        )
        assertTrue(product.valid())
        assertEquals("18,5–20,5 CNY", product.originalPrice())
        assertEquals("от ≈ 222 ₽", product.rubles())
        assertEquals("≈ 222 ₽", product.copy(priceMax = null).rubles())
        assertEquals("Цена по запросу", product.copy(priceMin = null).originalPrice())
        assertNull(product.copy(priceRub = null).rubles())
        assertEquals("taobao:42", product.key)
        assertEquals("1688:42", product.copy(source = "1688").key)
    }

    @Test
    fun briefResultCannotReplaceAnAvailableDetailedSnapshot() {
        val brief = Product(
            "taobao", "42", "台灯", "https://item.taobao.com/item.htm?id=42",
            "2026-11-24T03:00:00+08:00", null, null, "18.5", null, "CNY", null,
            null, null, null, null,
        )
        val detailed = brief.copy(titleRu = "Настольная лампа", seller = "Склад", isDetailed = true)
        assertFalse(brief.canReplace(detailed))
        assertTrue(detailed.canReplace(brief))
        assertTrue(brief.canReplace(null))
        assertFalse(brief.copy(observedAt = "").canReplace(brief))
    }

    @Test
    fun ocrKeepsRecognizedTextWhenCoordinatesAreMissingOrInvalid() {
        val response = OcrResponse(320, 240, listOf(
            null,
            OcrBlockResponse(null, "Лишняя строка", listOf(10, 10, 20, 10)),
            OcrBlockResponse("灯", "Лампа", listOf(10, 20, 100, 80)),
            OcrBlockResponse("桌", null, listOf(10, null, 20, 10)),
            OcrBlockResponse("灯具", " ", null),
            OcrBlockResponse("台灯", "Настольная лампа", listOf(300, 20, Int.MAX_VALUE, 100)),
        )).validated()

        assertEquals(4, response.blocks.size)
        assertEquals(OcrBounds(10, 20, 100, 80), response.blocks.first().box)
        assertEquals("桌", response.blocks[1].text)
        assertNull(response.blocks[2].translation)
        assertTrue(response.blocks.drop(1).all { it.box == null })
        assertThrows(IllegalArgumentException::class.java) { OcrResponse(0, 50, emptyList()).validated() }
        assertThrows(IllegalArgumentException::class.java) { OcrResponse(50, 50, null).validated() }
    }
}
