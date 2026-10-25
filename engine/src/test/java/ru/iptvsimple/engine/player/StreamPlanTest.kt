package ru.iptvsimple.engine.player

import androidx.media3.common.PlaybackException
import ru.iptvsimple.engine.player.StreamFormat.HLS
import ru.iptvsimple.engine.player.StreamFormat.PROGRESSIVE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamPlanTest {

    @Test
    fun `m3u8 в пути — сначала HLS`() {
        assertEquals(listOf(HLS, PROGRESSIVE), StreamPlan.formatOrder("http://host/live/index.m3u8"))
        assertEquals(listOf(HLS, PROGRESSIVE), StreamPlan.formatOrder("HTTP://HOST/Live/Index.M3U8?token=1"))
        assertEquals(listOf(HLS, PROGRESSIVE), StreamPlan.formatOrder("http://host/a.m3u8|User-Agent=x"))
    }

    @Test
    fun `без m3u8 в пути — сначала обычный поток`() {
        assertEquals(listOf(PROGRESSIVE, HLS), StreamPlan.formatOrder("http://host/live/stream.ts"))
        assertEquals(listOf(PROGRESSIVE, HLS), StreamPlan.formatOrder("http://host:8080/play/123"))
        // .m3u8 только в параметрах или в имени сервера не считается.
        assertEquals(listOf(PROGRESSIVE, HLS), StreamPlan.formatOrder("http://host/play?type=.m3u8"))
        assertEquals(listOf(PROGRESSIVE, HLS), StreamPlan.formatOrder("http://a.m3u8.example/"))
    }

    @Test
    fun `в запасе ровно одна другая попытка`() {
        listOf("http://h/a.m3u8", "http://h/a.ts").forEach { url ->
            val order = StreamPlan.formatOrder(url)
            assertEquals(2, order.size)
            assertEquals(2, order.toSet().size)
        }
    }

    @Test
    fun `заголовки из ссылки и User-Agent по умолчанию`() {
        val plain = StreamPlan.request("http://host/a.m3u8", "SimpleTV/1.0")
        assertEquals("http://host/a.m3u8", plain.url)
        assertEquals("SimpleTV/1.0", plain.userAgent)
        assertEquals(emptyMap(), plain.headers)
        assertEquals(listOf(HLS, PROGRESSIVE), plain.formats)

        val custom = StreamPlan.request(
            "http://host/a.ts|user-agent=Player/2&referrer=http://site/&X-Token=a=b",
            "SimpleTV/1.0",
        )
        assertEquals("http://host/a.ts", custom.url)
        assertEquals("Player/2", custom.userAgent)
        // User-Agent уходит отдельно, в остальных заголовках его нет.
        assertEquals(mapOf("Referer" to "http://site/", "X-Token" to "a=b"), custom.headers)
        assertEquals(listOf(PROGRESSIVE, HLS), custom.formats)
    }

    @Test
    fun `пустой User-Agent из ссылки заменяется нашим`() {
        val request = StreamPlan.request("http://host/a.m3u8|User-Agent=", "SimpleTV/1.0")
        assertEquals("SimpleTV/1.0", request.userAgent)
    }

    @Test
    fun `заголовки не из ASCII отбрасываются, User-Agent берётся наш`() {
        val request = StreamPlan.request(
            "http://host/a.m3u8|User-Agent=%D0%9F%D0%BB%D0%B5%D0%B5%D1%80&Referer=http://сайт.рф/&X-Ok=1",
            "SimpleTV/1.0",
        )
        assertEquals("SimpleTV/1.0", request.userAgent)
        assertEquals(mapOf("X-Ok" to "1"), request.headers)
        assertEquals(listOf("User-Agent", "Referer"), request.dropped)
    }

    @Test
    fun `другой формат пробуем только при ошибке разбора`() {
        assertTrue(StreamPlan.isFormatMismatch(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
        assertTrue(StreamPlan.isFormatMismatch(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED))
        assertTrue(StreamPlan.isFormatMismatch(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED))
        assertTrue(StreamPlan.isFormatMismatch(PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED))
        assertFalse(StreamPlan.isFormatMismatch(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertFalse(StreamPlan.isFormatMismatch(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
    }
}
