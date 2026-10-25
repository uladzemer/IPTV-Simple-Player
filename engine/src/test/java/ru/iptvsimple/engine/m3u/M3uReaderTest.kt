package ru.iptvsimple.engine.m3u

import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3uReaderTest {

    private fun parse(text: String): List<M3uEntry> =
        M3uReader.read(text.byteInputStream(Charsets.UTF_8)).toList()

    @Test
    fun `кириллица в названии и группе читается без искажений`() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Новости",Первый канал
            http://example.ru/perviy.m3u8
            """.trimIndent()
        )
        assertEquals(1, entries.size)
        assertEquals("Первый канал", entries[0].title)
        assertEquals("Новости", entries[0].group)
        assertEquals("http://example.ru/perviy.m3u8", entries[0].streamUrl)
    }

    @Test
    fun `группа берётся из group-title, а при его отсутствии из EXTGRP`() {
        val entries = parse(
            """
            #EXTM3U
            #EXTINF:-1,Канал А
            #EXTGRP:Спорт
            http://a/1.m3u8
            #EXTINF:-1 group-title="Кино",Канал Б
            #EXTGRP:Спорт
            http://a/2.m3u8
            #EXTINF:-1,Канал В
            http://a/3.m3u8
            """.trimIndent()
        )
        assertEquals(listOf("Спорт", "Кино", ""), entries.map { it.group })
    }

    @Test
    fun `запятая внутри кавычек не обрезает название`() {
        val entries = parse(
            """
            #EXTINF:-1 tvg-id="ch.1" tvg-logo="http://logo/a,b.png" group-title="Фильмы, сериалы",Кино, вино и домино
            http://a/kino.ts
            """.trimIndent()
        )
        val entry = entries.single()
        assertEquals("Кино, вино и домино", entry.title)
        assertEquals("Фильмы, сериалы", entry.group)
        assertEquals("http://logo/a,b.png", entry.logo)
        assertEquals("ch.1", entry.tvgId)
    }

    @Test
    fun `атрибуты без кавычек и ключи в любом регистре`() {
        val entry = parse(
            """
            #EXTINF:-1 TVG-ID=abc Group-Title=Музыка tvg-logo=http://l/x.png,Муз ТВ
            http://a/muz.m3u8
            """.trimIndent()
        ).single()
        assertEquals("abc", entry.tvgId)
        assertEquals("Музыка", entry.group)
        assertEquals("http://l/x.png", entry.logo)
        assertEquals("Муз ТВ", entry.title)
    }

    @Test
    fun `метка BOM в начале файла не мешает`() {
        val entries = parse("﻿#EXTM3U\n#EXTINF:-1,Канал\nhttp://a/1.m3u8\n")
        assertEquals("Канал", entries.single().title)
    }

    @Test
    fun `переводы строк CRLF и CR`() {
        val crlf = parse("#EXTM3U\r\n#EXTINF:-1,Один\r\nhttp://a/1\r\n#EXTINF:-1,Два\r\nhttp://a/2\r\n")
        assertEquals(listOf("Один", "Два"), crlf.map { it.title })
        assertEquals(listOf("http://a/1", "http://a/2"), crlf.map { it.streamUrl })

        val cr = parse("#EXTM3U\r#EXTINF:-1,Один\rhttp://a/1\r")
        assertEquals("Один", cr.single().title)
    }

    @Test
    fun `пустые и битые строки пропускаются`() {
        val entries = parse(
            """
            #EXTM3U

            #EXTINF
            #EXTINF:
            #EXTINF:-1 tvg-logo="без закрывающей кавычки
            #EXTVLCOPT:http-user-agent=Test

            http://a/1.m3u8
            #EXTINF:-1 ="пустой ключ" просто_слово,Нормальный
            http://a/2.m3u8
            #
            """.trimIndent()
        )
        assertEquals(2, entries.size)
        // У первой записи названия нет — подставляется ссылка.
        assertEquals("http://a/1.m3u8", entries[0].title)
        assertEquals("Нормальный", entries[1].title)
    }

    @Test
    fun `файл без заголовка EXTM3U тоже читается`() {
        val entries = parse("#EXTINF:-1,Канал\nhttp://a/1.m3u8")
        assertEquals("Канал", entries.single().title)
    }

    @Test
    fun `ссылка без EXTINF получает название по ссылке`() {
        val entries = parse("#EXTM3U\nhttp://a/live.m3u8|User-Agent=X\n#EXTINF:-1,Второй\nhttp://a/2")
        assertEquals(2, entries.size)
        assertEquals("http://a/live.m3u8", entries[0].title)
        assertEquals("http://a/live.m3u8|User-Agent=X", entries[0].streamUrl)
        assertEquals("", entries[0].group)
        assertNull(entries[0].logo)
        assertNull(entries[0].tvgId)
        assertEquals("Второй", entries[1].title)
    }

    @Test
    fun `большой плейлист на 5000 каналов читается целиком и по порядку`() {
        val text = buildString {
            append("#EXTM3U\n")
            repeat(5000) { i ->
                append("#EXTINF:-1,Канал $i\n#EXTGRP:Группа ${i % 7}\nhttp://a/$i.m3u8\n")
            }
        }
        val entries = parse(text)
        assertEquals(5000, entries.size)
        assertEquals("Канал 4999", entries.last().title)
        assertEquals("Группа ${4999 % 7}", entries.last().group)
    }

    @Test
    fun `чтение ленивое - бесконечный файл не читается до конца`() {
        val endless = EndlessPlaylist()
        val firstTen = M3uReader.read(endless).take(10).toList()
        assertEquals(10, firstTen.size)
        assertEquals("Канал 9", firstTen.last().title)
        // Прочитано не больше нескольких буферов, а не весь «файл».
        assertTrue(endless.bytesRead < 64 * 1024, "прочитано ${endless.bytesRead} байт")
    }

    /** Поток, который выдаёт записи плейлиста без конца. */
    private class EndlessPlaylist : InputStream() {
        private var chunk = "#EXTM3U\n".toByteArray()
        private var offset = 0
        private var index = 0
        var bytesRead = 0L
            private set

        override fun read(): Int {
            if (offset == chunk.size) {
                chunk = "#EXTINF:-1,Канал $index\nhttp://a/$index.m3u8\n".toByteArray()
                index++
                offset = 0
            }
            bytesRead++
            return chunk[offset++].toInt() and 0xFF
        }
    }
}
