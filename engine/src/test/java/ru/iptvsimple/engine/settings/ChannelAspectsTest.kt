package ru.iptvsimple.engine.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelAspectsTest {

    @Test
    fun `пропорции сохраняются и читаются обратно`() {
        val aspects = mapOf(12L to ChannelAspect.CLASSIC, 3L to ChannelAspect.FILM)
        assertEquals("3:2,12:1", encodeChannelAspects(aspects))
        assertEquals(aspects, decodeChannelAspects(encodeChannelAspects(aspects)))
    }

    @Test
    fun `«Авто» не записывается`() {
        assertEquals("", encodeChannelAspects(mapOf(5L to ChannelAspect.AUTO)))
    }

    @Test
    fun `испорченная строка не роняет чтение`() {
        assertEquals(emptyMap(), decodeChannelAspects(null))
        assertEquals(mapOf(7L to ChannelAspect.CLASSIC), decodeChannelAspects("x:1,7:1,8:99,9,10:0"))
    }

    @Test
    fun `варианты идут по кругу`() {
        assertEquals(ChannelAspect.CLASSIC, ChannelAspect.AUTO.next())
        assertEquals(ChannelAspect.FILM, ChannelAspect.CLASSIC.next())
        assertEquals(ChannelAspect.AUTO, ChannelAspect.FILM.next())
    }
}
