package ru.iptvsimple.engine.m3u

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamUrlTest {

    @Test
    fun `ссылка без параметров остаётся как есть`() {
        val parsed = StreamUrl.parse("  http://host/live/index.m3u8  ")
        assertEquals("http://host/live/index.m3u8", parsed.url)
        assertEquals(emptyMap(), parsed.headers)
        assertNull(parsed.userAgent)
    }

    @Test
    fun `User-Agent в любом регистре`() {
        listOf("User-Agent", "user-agent", "USER-AGENT", "uSeR-aGeNt").forEach { name ->
            val parsed = StreamUrl.parse("http://host/a.m3u8|$name=Плеер/1.0")
            assertEquals("http://host/a.m3u8", parsed.url)
            assertEquals("Плеер/1.0", parsed.userAgent, "имя $name")
            assertEquals(setOf("User-Agent"), parsed.headers.keys)
        }
    }

    @Test
    fun `значение со знаком равенства делится по первому`() {
        val parsed = StreamUrl.parse("http://host/a.ts|Cookie=token=abc==")
        assertEquals("token=abc==", parsed.headers["Cookie"])
    }

    @Test
    fun `несколько параметров через амперсанд`() {
        val parsed = StreamUrl.parse(
            "http://host/a.m3u8|User-Agent=Mozilla%2F5.0&Referer=http://site/page&origin=http://site"
        )
        assertEquals("http://host/a.m3u8", parsed.url)
        assertEquals(
            mapOf(
                "User-Agent" to "Mozilla/5.0",
                "Referer" to "http://site/page",
                "Origin" to "http://site",
            ),
            parsed.headers,
        )
    }

    @Test
    fun `пустые и битые параметры пропускаются`() {
        val parsed = StreamUrl.parse("http://host/a.m3u8|&=x&Referrer=http://r/&просто")
        assertEquals(mapOf("Referer" to "http://r/"), parsed.headers)
        assertEquals("http://host/a.m3u8", StreamUrl.withoutHeaders("http://host/a.m3u8|a=b"))
    }
}
