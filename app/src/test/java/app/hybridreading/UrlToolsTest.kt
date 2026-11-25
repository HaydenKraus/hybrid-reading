package app.hybridreading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlToolsTest {
    @Test
    fun bareDomainGetsHttpsAndPathKept() {
        assertEquals("https://example.net/foo", UrlTools.normalize("example.net/foo"))
    }

    @Test
    fun bareDomainWithoutPathGetsSlash() {
        assertEquals("https://example.me/", UrlTools.normalize("example.me"))
    }

    @Test
    fun httpSchemeKeptAndEmptyPathFilled() {
        assertEquals("http://x.ru/", UrlTools.normalize("http://x.ru"))
    }

    @Test
    fun uppercaseSchemeAndSurroundingSpaces() {
        assertEquals("http://www.example.net/", UrlTools.normalize("  HTTP://WWW.EXAMPLE.NET  "))
    }

    @Test
    fun portKept() {
        assertEquals("https://example.com:8443/a", UrlTools.normalize("https://example.com:8443/a"))
    }

    @Test
    fun queryKept() {
        assertEquals("https://example.com/search?q=1", UrlTools.normalize("example.com/search?q=1"))
    }

    @Test
    fun fragmentDropped() {
        assertEquals("https://example.com/a", UrlTools.normalize("https://example.com/a#top"))
    }

    @Test
    fun loginAndPasswordDropped() {
        assertEquals("https://site.ru/x", UrlTools.normalize("https://user:pass@site.ru/x"))
    }

    @Test
    fun spacesInPathAndQueryEncoded() {
        assertEquals("http://x.ru/a%20b", UrlTools.normalize("http://x.ru/a b"))
        assertEquals("https://example.com/s?q=a%20b", UrlTools.normalize("example.com/s?q=a b"))
    }

    @Test
    fun cyrillicDomainAllowed() {
        assertEquals("https://пример.рф/", UrlTools.normalize("пример.рф"))
        assertEquals("пример.рф", UrlTools.host("пример.рф/книга"))
    }

    @Test
    fun javascriptSchemeRejected() {
        assertNull(UrlTools.normalize("javascript:alert(1)"))
    }

    @Test
    fun unsupportedSchemeRejected() {
        assertNull(UrlTools.normalize("ftp://example.com"))
        assertNull(UrlTools.normalize("mailto:someone@example.com"))
    }

    @Test
    fun blankRejected() {
        assertNull(UrlTools.normalize(""))
        assertNull(UrlTools.normalize("   "))
    }

    @Test
    fun hostWithoutDotRejected() {
        assertNull(UrlTools.normalize("localhost"))
        assertNull(UrlTools.normalize("https://localhost/x"))
    }

    @Test
    fun emptyHostRejected() {
        assertNull(UrlTools.normalize("https://"))
        assertNull(UrlTools.normalize("http:///a"))
        assertNull(UrlTools.normalize("https://exa mple.ru/"))
    }

    @Test
    fun invalidPortRejected() {
        assertNull(UrlTools.normalize("https://example.com:abc/"))
        assertNull(UrlTools.normalize("https://example.com:70000/"))
    }

    @Test
    fun hostStripsWwwPortAndCase() {
        assertEquals("example.net", UrlTools.host("https://WWW.Example.net/x"))
        assertEquals("example.com", UrlTools.host("https://example.com:8080/"))
    }

    @Test
    fun hostInvalidReturnsNull() {
        assertNull(UrlTools.host("nonsense"))
        assertNull(UrlTools.host(""))
    }

    @Test
    fun idForEqualsHost() {
        assertEquals("example.me", UrlTools.idFor("https://www.example.me/"))
        assertEquals(UrlTools.host("https://example.net/"), UrlTools.idFor("https://example.net/"))
        assertNull(UrlTools.idFor("javascript:alert(1)"))
    }

    @Test
    fun titleFromDomain() {
        assertEquals("Example", UrlTools.titleFor("https://example.net/"))
        assertEquals("Example", UrlTools.titleFor("https://www.example.me/"))
        assertEquals("Example", UrlTools.titleFor("https://example.net/foo?x=1"))
    }

    @Test
    fun titleFallsBackToDefault() {
        assertEquals("Сайт", UrlTools.titleFor("javascript:alert(1)"))
        assertEquals("Сайт", UrlTools.titleFor(""))
    }

    @Test
    fun titleCapitalizedAndTruncated() {
        val title = UrlTools.titleFor("https://verylongdomainnameexample.com/")
        assertTrue(title.length <= 24)
        assertEquals("V", title.take(1))
        assertEquals("Пример", UrlTools.titleFor("https://пример.рф/"))
    }
}
