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

/** Скрытые блоки: хранение по сайтам, дубликаты, CSS и очистка. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HiddenBlocksTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HiddenBlocks.clear(context, "example.net")
    }

    @Test
    fun emptyByDefault() {
        assertEquals(emptyList<String>(), HiddenBlocks.get(context, "example.net"))
        assertEquals(0, HiddenBlocks.count(context, "example.net"))
        assertEquals("", HiddenBlocks.css(context, "example.net"))
    }

    @Test
    fun addAndReadBack() {
        assertTrue(HiddenBlocks.add(context, "example.net", "div.banner"))
        assertTrue(HiddenBlocks.add(context, "example.net", "#promo"))

        assertEquals(listOf("div.banner", "#promo"), HiddenBlocks.get(context, "example.net"))
        assertEquals(2, HiddenBlocks.count(context, "example.net"))
    }

    @Test
    fun duplicatesIgnored() {
        assertTrue(HiddenBlocks.add(context, "example.net", "div.banner"))
        assertFalse(HiddenBlocks.add(context, "example.net", "div.banner"))
        assertFalse(HiddenBlocks.add(context, "example.net", "   "))
        assertEquals(1, HiddenBlocks.count(context, "example.net"))
    }

    @Test
    fun blocksAreSeparatePerSite() {
        HiddenBlocks.add(context, "example.net", "div.banner")
        HiddenBlocks.add(context, "example.me", "aside.ad")

        assertEquals(listOf("div.banner"), HiddenBlocks.get(context, "example.net"))
        assertEquals(listOf("aside.ad"), HiddenBlocks.get(context, "example.me"))
    }

    @Test
    fun cssHidesEverySelector() {
        HiddenBlocks.add(context, "example.net", "div.banner")
        HiddenBlocks.add(context, "example.net", "#promo")

        assertEquals(
            "div.banner{display:none!important}#promo{display:none!important}",
            HiddenBlocks.css(context, "example.net"),
        )
    }

    @Test
    fun clearRemovesOnlyOneSite() {
        HiddenBlocks.add(context, "example.net", "div.banner")
        HiddenBlocks.add(context, "example.me", "aside.ad")

        HiddenBlocks.clear(context, "example.net")

        assertEquals(0, HiddenBlocks.count(context, "example.net"))
        assertEquals(1, HiddenBlocks.count(context, "example.me"))
    }
}
