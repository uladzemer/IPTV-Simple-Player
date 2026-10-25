package ru.iptvsimple.engine.player

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveHoldbackTest {

    private fun live(count: Int, end: String = "") = buildString {
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:100\n")
        for (i in 1..count) append("#EXTINF:10.0,\nseg$i.ts?md5=x\n")
        append(end)
    }

    @Test
    fun `живой плейлист теряет последний кусок`() {
        val trimmed = LiveHoldback.trim(live(6), segments = 1)
        assertEquals(live(5), trimmed)
    }

    @Test
    fun `служебные строки убранного куска уходят вместе с ним`() {
        val text = live(5) + "#EXT-X-DISCONTINUITY\n#EXT-X-PROGRAM-DATE-TIME:2026-10-25T20:00:00Z\n" +
            "#EXTINF:10.0,\nseg6.ts\n"
        assertEquals(live(5), LiveHoldback.trim(text, segments = 1))
    }

    @Test
    fun `короткий список не режем`() {
        val text = live(3)
        assertEquals(text, LiveHoldback.trim(text, segments = 1))
    }

    @Test
    fun `запись с концом, LL-HLS и блокирующую перезагрузку не трогаем`() {
        val vod = live(10, end = "#EXT-X-ENDLIST\n")
        assertEquals(vod, LiveHoldback.trim(vod))
        val lowLatency = live(10) + "#EXT-X-PART:DURATION=1.0,URI=\"p1.ts\"\n"
        assertEquals(lowLatency, LiveHoldback.trim(lowLatency))
        val blocking = live(10).replace(
            "#EXT-X-VERSION:3\n",
            "#EXT-X-VERSION:9\n#EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES\n",
        )
        assertEquals(blocking, LiveHoldback.trim(blocking))
    }

    @Test
    fun `переводы строк Windows не мешают`() {
        val trimmed = LiveHoldback.trim(live(6).replace("\n", "\r\n"), segments = 1)
        assertEquals(live(5), trimmed.replace("\r", ""))
    }

    @Test
    fun `главный плейлист с вариантами не меняется`() {
        val multivariant = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=3000000\nhigh.m3u8\n"
        assertEquals(multivariant, LiveHoldback.trim(multivariant))
    }
}
