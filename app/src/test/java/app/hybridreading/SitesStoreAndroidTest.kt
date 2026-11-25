package app.hybridreading

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Хранилище сайтов и профиль: добавление, правка, лимит кэша, экспорт и импорт. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SitesStoreAndroidTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("sites", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("hidden_blocks", Context.MODE_PRIVATE).edit().clear().commit()
        SitesStore.init(context)
        SitesStore.replaceAll(emptyList())
    }

    @Test
    fun addNormalisesAndSelects() {
        val site = SitesStore.add("example.net", null).getOrThrow()

        assertEquals("example.net", site.id)
        assertEquals("https://example.net/", site.url)
        assertEquals(0, site.cacheLimitMb)
        assertEquals("example.net", SitesStore.active.value?.id)
    }

    @Test
    fun limitIsStoredAndUpdated() {
        SitesStore.add("example.net", "Пример", 200)
        assertEquals(200, SitesStore.sites.value.first().cacheLimitMb)

        SitesStore.update("example.net", "https://example.net/", "Пример", 50)
        assertEquals(50, SitesStore.sites.value.first().cacheLimitMb)
        assertEquals("Пример", SitesStore.sites.value.first().title)
    }

    @Test
    fun duplicateDomainRejectedOnUpdate() {
        SitesStore.add("example.net", null)
        SitesStore.add("example.me", null)

        val result = SitesStore.update("example.me", "https://example.net/", null, 0)

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun removeDropsSiteAndActiveFallsBack() {
        SitesStore.add("example.net", null)
        SitesStore.add("example.me", null)

        SitesStore.remove("example.me")

        assertEquals(listOf("example.net"), SitesStore.sites.value.map { it.id })
        assertEquals("example.net", SitesStore.active.value?.id)

        SitesStore.remove("example.net")
        assertTrue(SitesStore.sites.value.isEmpty())
        assertNull(SitesStore.active.value)
    }

    @Test
    fun backupRoundTripRestoresSitesAndBlocks() {
        SitesStore.add("example.net", "Первый", 100)
        SitesStore.add("example.me", "Второй", 0)
        HiddenBlocks.add(context, "example.net", "div.banner")
        HiddenBlocks.add(context, "example.net", "#promo")

        val json = ProfileBackup.buildJson(context)
        SitesStore.replaceAll(emptyList())
        HiddenBlocks.clear(context, "example.net")

        val summary = ProfileBackup.restore(context, json)

        assertTrue(summary.contains("Сайтов: 2"))
        assertEquals(listOf("example.net", "example.me"), SitesStore.sites.value.map { it.id })
        assertEquals(100, SitesStore.sites.value.first().cacheLimitMb)
        assertEquals(listOf("div.banner", "#promo"), HiddenBlocks.get(context, "example.net"))
    }

    @Test
    fun brokenProfileIsRejected() {
        val result = runCatching { ProfileBackup.restore(context, "не json") }
        assertTrue(result.isFailure)
    }

    @Test
    fun versionComparison() {
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.0"))
        assertTrue(UpdateChecker.isNewer("2.0", "1.99.9"))
        assertFalse(UpdateChecker.isNewer("1.9.0", "1.9.0"))
        assertFalse(UpdateChecker.isNewer("1.8.9", "1.9.0"))
    }
}
