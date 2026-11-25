package app.hybridreading

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** История и закладки. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        History.clear(context)
        context.getSharedPreferences("history", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun newestFirstAndNoDuplicates() {
        History.add(context, "https://example.net/1", "Первая", "example.net")
        History.add(context, "https://example.net/2", "Вторая", "example.net")
        History.add(context, "https://example.net/1", "Первая обновлённая", "example.net")

        val list = History.list(context)
        assertEquals(2, list.size)
        assertEquals("https://example.net/1", list[0].url)
        assertEquals("Первая обновлённая", list[0].title)
    }

    @Test
    fun searchFiltersByTitleAndUrl() {
        History.add(context, "https://example.net/alpha", "Глава про драконов", "example.net")
        History.add(context, "https://example.me/beta", "Совсем другое", "example.me")

        assertEquals(1, History.list(context, "дракон").size)
        assertEquals(1, History.list(context, "example.me").size)
        assertEquals(0, History.list(context, "нет такого").size)
    }

    @Test
    fun nonHttpIgnored() {
        History.add(context, "javascript:alert(1)", "bad", "example.net")
        assertTrue(History.list(context).isEmpty())
    }

    @Test
    fun bookmarksToggle() {
        val url = "https://example.net/chapter"
        assertFalse(History.isBookmarked(context, url))

        assertTrue(History.toggleBookmark(context, url, "Глава 1"))
        assertTrue(History.isBookmarked(context, url))
        assertEquals(1, History.bookmarks(context).size)

        assertFalse(History.toggleBookmark(context, url, "Глава 1"))
        assertFalse(History.isBookmarked(context, url))
    }

    @Test
    fun clearKeepsBookmarks() {
        History.add(context, "https://example.net/1", "Страница", "example.net")
        History.toggleBookmark(context, "https://example.net/1", "Страница")

        History.clear(context)

        assertTrue(History.list(context).isEmpty())
        assertEquals(1, History.bookmarks(context).size)
    }
}
