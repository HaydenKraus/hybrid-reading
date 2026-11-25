package app.hybridreading

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatBytesTest {
    @Test
    fun smallValuesAreBytes() {
        assertEquals("0 Б", formatBytes(0))
        assertEquals("512 Б", formatBytes(512))
        assertEquals("1023 Б", formatBytes(1023))
    }

    @Test
    fun kilobytesAndMegabytes() {
        assertEquals("1 КБ", formatBytes(1024))
        assertEquals("6,3 МБ", formatBytes(6_600_000))
        assertEquals("12 МБ", formatBytes(12 * 1024 * 1024))
    }

    @Test
    fun gigabytesForHugeCache() {
        assertEquals("1,5 ГБ", formatBytes(1536L * 1024 * 1024))
    }

    @Test
    fun negativeMeansUnknown() {
        assertEquals("—", formatBytes(-1))
    }
}
