package app.hybridreading

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SitesStoreTest {
    @Test
    fun emptyOrBrokenJsonGivesEmptyList() {
        assertTrue(decodeSites(null).isEmpty())
        assertTrue(decodeSites("").isEmpty())
        assertTrue(decodeSites("[]").isEmpty())
        assertTrue(decodeSites("not a json").isEmpty())
        assertTrue(decodeSites("[{\"id\":\"x\",").isEmpty())
        assertTrue(decodeSites("{}").isEmpty())
    }

    @Test
    fun orderIsPreserved() {
        val json =
            """
            [{"id":"example.com","title":"Example","url":"https://example.com/"},
             {"id":"example.net","title":"Example","url":"https://example.net/"}]
            """.trimIndent()
        val list = decodeSites(json)
        assertEquals(listOf("example.com", "example.net"), list.map { it.id })
    }

    @Test
    fun roundTripKeepsOrderAndTitles() {
        val src =
            listOf(
                Site("example.net", "Example", "https://example.net/"),
                Site("example.com", "Пример", "https://example.com/"),
                Site("example.me", "Манга", "https://example.me/"),
            )
        assertEquals(src, decodeSites(encodeSites(src)))
    }

    @Test
    fun duplicatesByIdAreDroppedKeepingFirst() {
        val json =
            """
            [{"id":"example.com","title":"A","url":"https://example.com/"},
             {"id":"example.com","title":"B","url":"https://example.com/other"}]
            """.trimIndent()
        val list = decodeSites(json)
        assertEquals(1, list.size)
        assertEquals(Site("example.com", "A", "https://example.com/"), list[0])
    }

    @Test
    fun invalidUrlEntrySkipped() {
        val json =
            """
            [{"id":"bad","title":"Bad","url":"javascript:alert(1)"},
             {"id":"ok.ru","title":"OK","url":"ok.ru"}]
            """.trimIndent()
        assertEquals(listOf(Site("ok.ru", "OK", "https://ok.ru/")), decodeSites(json))
    }

    @Test
    fun idAndTitleDerivedFromUrlWhenMissing() {
        val json = """[{"url":"https://example.com/"}]"""
        assertEquals(listOf(Site("example.com", "Example", "https://example.com/")), decodeSites(json))
    }

    @Test
    fun escapesSurviveRoundTrip() {
        val weird = Site("example.com", "Цитата \"тут\"\nи перенос", "https://example.com/")
        val decoded = decodeSites(encodeSites(listOf(weird)))
        assertEquals(weird, decoded[0])
    }

    @Test
    fun encodedProxyValuesSkippedWithoutCrash() {
        // В объектах могут оказаться нестроковые поля — разбор не должен падать.
        val json = """[{"id":"example.com","n":1,"ok":true,"tags":["a"],"url":"https://example.com/"}]"""
        val list = decodeSites(json)
        assertEquals(1, list.size)
        assertEquals("Example", list[0].title)
    }
}
