package app.hybridreading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerTest {

    @Test
    fun blocksYandexAdHosts() {
        assertTrue(AdBlocker.shouldBlock("https://an.yandex.ru/count/abc"))
        assertTrue(AdBlocker.shouldBlock("https://ads.yandex.ru/x.gif"))
        assertTrue(AdBlocker.shouldBlock("https://adfox.yandex.ru/slot"))
        assertTrue(AdBlocker.shouldBlock("https://mc.yandex.ru/metrika/tag.js"))
    }

    @Test
    fun blocksYandexAdsOnlyOnAdPath() {
        assertTrue(AdBlocker.shouldBlock("https://yandex.ru/ads/system/context.js"))
        // Тот же домен, но обычная страница — гасить нельзя.
        assertFalse(AdBlocker.shouldBlock("https://yandex.ru/search/?text=example"))
        assertFalse(AdBlocker.shouldBlock("https://passport.yandex.ru/auth"))
    }

    @Test
    fun blocksGoogleAdAndTrackerHosts() {
        assertTrue(AdBlocker.shouldBlock("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(AdBlocker.shouldBlock("https://googleads.g.doubleclick.net/pagead/id"))
        assertTrue(AdBlocker.shouldBlock("https://www.google-analytics.com/analytics.js"))
        assertTrue(AdBlocker.shouldBlock("https://www.googletagmanager.com/gtm.js?id=GTM-1"))
    }

    @Test
    fun blocksOtherAdNetworks() {
        assertTrue(AdBlocker.shouldBlock("https://criteo.com/x.js"))
        assertTrue(AdBlocker.shouldBlock("https://cdn.taboola.com/libtrc/x.js"))
        assertTrue(AdBlocker.shouldBlock("https://s.amazon-adsystem.com/iu3"))
        assertTrue(AdBlocker.shouldBlock("https://ib.adnxs.com/getuid"))
    }

    @Test
    fun yandexNetImagesAreNotBlocked() {
        // avatars.yandex.net — картинки аватарок, их блокировка ломает вёрстку сайтов.
        assertFalse(AdBlocker.shouldBlock("https://avatars.yandex.net/get-yapic/1/2/orig"))
        // Но служебные поддомены рекламной сети — да.
        assertTrue(AdBlocker.shouldBlock("https://an.yandex.net/ads/1"))
    }

    @Test
    fun siteAndCdnAreNeverBlocked() {
        assertFalse(AdBlocker.shouldBlock("https://example.net/content/book/1234"))
        assertFalse(AdBlocker.shouldBlock("https://cdn.example.net/chapters/x/1.png"))
        assertFalse(AdBlocker.shouldBlock("https://cdn.jsdelivr.net/npm/x.js"))
        assertFalse(AdBlocker.shouldBlock("https://fonts.gstatic.com/s/x.woff2"))
        assertFalse(AdBlocker.shouldBlock("https://example.com/ads/banner.png"))
    }

    @Test
    fun authHostsAreNotBlocked() {
        assertFalse(AdBlocker.shouldBlock("https://oauth.telegram.org/auth?bot_id=1"))
        assertFalse(AdBlocker.shouldBlock("https://accounts.google.com/o/oauth2/v2/auth"))
        assertFalse(AdBlocker.shouldBlock("https://oauth.vk.com/authorize"))
        assertFalse(AdBlocker.shouldBlock("https://discord.com/oauth2/authorize"))
        assertFalse(AdBlocker.shouldBlock("https://appleid.apple.com/auth/authorize"))
    }

    @Test
    fun trackerOnlyOnItsAdPaths() {
        assertTrue(AdBlocker.shouldBlock("https://app.appsflyer.com/ads/impression"))
        assertFalse(AdBlocker.shouldBlock("https://app.appsflyer.com/sdk/v1"))
    }

    @Test
    fun ownSiteRequestsAreNeverBlocked() {
        // CDN, картинки глав и офлайн-кэш самого сайта блокировать нельзя — иначе главы не скачаются.
        assertFalse(AdBlocker.shouldBlock("https://example.net/_next/static/chunk.js", "example.net"))
        assertFalse(AdBlocker.shouldBlock("https://cdn.example.net/chapters/1.png", "example.net"))
        assertFalse(AdBlocker.shouldBlock("https://img.example.me/x.webp", "example.me"))
        // А реклама даже рядом с «своим» сайтом режется.
        assertTrue(AdBlocker.shouldBlock("https://an.yandex.ru/slot", "example.net"))
        assertTrue(AdBlocker.shouldBlock("https://an.yandex.ru/slot", "example.me"))
    }

    @Test
    fun garbageDoesNotCrash() {
        assertFalse(AdBlocker.shouldBlock(""))
        assertFalse(AdBlocker.shouldBlock("   "))
        assertFalse(AdBlocker.shouldBlock("not a url"))
        assertFalse(AdBlocker.shouldBlock("data:image/png;base64,AAAA"))
    }

    @Test
    fun mimeMatchesExtension() {
        assertEquals("image/png", AdBlocker.mimeFor("https://an.yandex.ru/x.png"))
        assertEquals("text/javascript", AdBlocker.mimeFor("https://an.yandex.ru/x.js"))
        assertEquals("text/css", AdBlocker.mimeFor("https://an.yandex.ru/x.css?1"))
        assertEquals("image/jpeg", AdBlocker.mimeFor("https://an.yandex.ru/x.jpeg"))
        // Без расширения считаем скриптом: пустышка не должна ломать парсинг HTML.
        assertEquals("text/javascript", AdBlocker.mimeFor("https://an.yandex.ru/slot?id=1"))
    }
}
