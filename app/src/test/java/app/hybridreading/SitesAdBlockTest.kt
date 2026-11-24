package app.hybridreading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Хранение флага «блокировать рекламу» по каждому сайту. */
class SitesAdBlockTest {

    @Test
    fun adBlockSurvivesRoundTrip() {
        val sites = listOf(
            Site("example.net", "Example", "https://example.net/", adBlock = false),
            Site("example.me", "Пример", "https://example.me/", adBlock = false),
            Site("example.com", "Третий", "https://example.com/", adBlock = true),
        )
        val restored = decodeSites(encodeSites(sites))

        assertEquals(3, restored.size)
        assertFalse(restored[0].adBlock)
        assertFalse(restored[1].adBlock)
        assertTrue(restored[2].adBlock)
    }

    @Test
    fun oldJsonWithoutFlagDefaultsToBlocking() {
        // Данные версий 1.3–1.6 не знали про флаг — после обновления реклама должна быть включена.
        val old = """[{"id":"example.net","title":"Example","url":"https://example.net/"},""" +
            """{"id":"example.me","title":"Пример","url":"https://example.me/"}]"""
        val restored = decodeSites(old)

        assertEquals(2, restored.size)
        assertTrue(restored[0].adBlock)
        assertTrue(restored[1].adBlock)
    }

    @Test
    fun customTitleAndFlagAreRestored() {
        val json = """[{"id":"example.net","title":"Мой сайт","url":"https://example.net/","adblock":false}]"""
        val restored = decodeSites(json)

        assertEquals("Мой сайт", restored[0].title)
        assertFalse(restored[0].adBlock)
    }
}
