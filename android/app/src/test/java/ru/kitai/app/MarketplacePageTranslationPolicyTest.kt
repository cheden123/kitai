package ru.kitai.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplacePageTranslationPolicyTest {
    @Test
    fun translatesSupportedPublicCatalogRoutes() {
        for (market in Market.entries) {
            assertTrue(isPublicMarketplacePage(market, market.homepage))
            assertTrue(isPublicMarketplacePage(market, market.searchUrl("настольная лампа")))
        }
        assertTrue(isPublicMarketplacePage(Market.TAOBAO, "https://detail.m.tmall.com/item.htm?id=42"))
        assertTrue(isPublicMarketplacePage(Market.ALI1688, "https://detail.1688.com/offer/43.html#details"))
        assertTrue(isPublicMarketplacePage(Market.ALIBABA, "https://m.alibaba.com/product/44/desk-lamp.html"))
        assertTrue(isPublicMarketplacePage(Market.ALIBABA, "https://russian.alibaba.com/g/desk-lamps.html"))
    }

    @Test
    fun excludesAccountRoutesAndLookalikeAddresses() {
        assertFalse(isPublicMarketplacePage(Market.TAOBAO, "https://login.taobao.com/item.htm?id=42"))
        assertFalse(isPublicMarketplacePage(Market.TAOBAO, "https://www.taobao.com/#/account"))
        assertFalse(isPublicMarketplacePage(Market.ALI1688, "https://www.1688.com/checkout"))
        assertFalse(isPublicMarketplacePage(Market.ALIBABA, "https://buyer.alibaba.com/product-detail/lamp_44.html"))
        assertFalse(isPublicMarketplacePage(Market.ALIBABA, "https://m.alibaba.com.example.org/product/44/lamp.html"))
        assertFalse(isPublicMarketplacePage(Market.ALIBABA, "https://www.taobao.com/"))
        assertFalse(isPublicMarketplacePage(Market.ALIBABA, "http://www.alibaba.com/"))
    }

    @Test
    fun decodesOnlyRecognizedSearchTerms() {
        assertEquals(
            listOf("灯 A&B", "desk lamp"),
            marketplaceSearchTerms("https://s.taobao.com/search?%71=%E7%81%AF+A%26B&SearchText=desk%20lamp&other=value"),
        )
    }

    @Test
    fun rejectsInvalidOrOversizedCaptureBeforeTranslation() {
        assertEquals(PageTranslationCapture(listOf("灯"), true), parsePageTranslationCapture("""{"texts":["灯"],"hasMore":true}"""))
        assertThrows(PageTranslationUnavailable::class.java) {
            parsePageTranslationCapture("""{"texts":[42],"hasMore":false}""")
        }
        val longText = "a".repeat(1_501)
        assertThrows(PageTranslationUnavailable::class.java) {
            parsePageTranslationCapture("""{"texts":["$longText"],"hasMore":false}""")
        }
        val texts = List(36) { "\"${"a".repeat(126)}\"" }.joinToString(",")
        assertThrows(PageTranslationUnavailable::class.java) {
            parsePageTranslationCapture("""{"texts":[$texts],"hasMore":false}""")
        }
    }
}
