package ru.iptvsimple.tv

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvStreamInfoTest {

    @Test
    fun `частота кадров без лишних нулей`() {
        assertEquals("25", tvFrameRateText(25f))
        assertEquals("50", tvFrameRateText(50.0001f))
        assertEquals("29.97", tvFrameRateText(29.97f))
        assertEquals("23.98", tvFrameRateText(23.976f))
        assertEquals("12.5", tvFrameRateText(12.5f))
        assertEquals("24.05", tvFrameRateText(24.05f))
    }

    @Test
    fun `неизвестная частота не показывается`() {
        assertNull(tvFrameRateText(-1f))
        assertNull(tvFrameRateText(0f))
        assertNull(tvFrameRateText(Float.NaN))
        assertNull(tvFrameRateText(Float.POSITIVE_INFINITY))
    }

    @Test
    fun `имена кодеков`() {
        assertEquals("H.264", tvVideoCodecName("video/avc"))
        assertEquals("MPEG-2", tvVideoCodecName("video/mpeg2"))
        assertNull(tvVideoCodecName("video/unknown"))
        assertNull(tvVideoCodecName(null))
    }
}
