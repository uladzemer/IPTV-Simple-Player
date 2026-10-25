package ru.iptvsimple.engine.m3u

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Разборщик плейлистов M3U/M3U8.
 *
 * Читает файл по строкам и отдаёт записи по одной, поэтому даже очень большой
 * плейлист не загружается в память целиком. Поток [InputStream] закрывает тот,
 * кто его открыл. Испорченные строки пропускаются, разбор при этом не падает.
 */
object M3uReader {

    private const val INFO_TAG = "#EXTINF:"
    private const val GROUP_TAG = "#EXTGRP:"
    private const val BYTE_ORDER_MARK = '﻿'

    fun read(input: InputStream): Sequence<M3uEntry> = sequence {
        // BufferedReader.readLine сам понимает переводы строк LF, CR и CRLF.
        val lines = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        var info: InfoLine? = null
        var groupFromTag: String? = null
        var firstLine = true
        while (true) {
            var line = lines.readLine() ?: break
            if (firstLine) {
                firstLine = false
                line = line.trimStart(BYTE_ORDER_MARK)
            }
            line = line.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith(INFO_TAG, ignoreCase = true) ->
                    info = parseInfo(line.substring(INFO_TAG.length))

                line.startsWith(GROUP_TAG, ignoreCase = true) ->
                    groupFromTag = line.substring(GROUP_TAG.length).trim().ifEmpty { null }

                line.startsWith("#") -> Unit
                else -> {
                    yield(buildEntry(info, groupFromTag, line))
                    info = null
                    groupFromTag = null
                }
            }
        }
    }

    private fun buildEntry(info: InfoLine?, groupFromTag: String?, link: String): M3uEntry {
        val attributes = info?.attributes.orEmpty()
        val title = info?.title?.takeIf { it.isNotBlank() }
            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
            ?: StreamUrl.parse(link).url
        val group = attributes["group-title"]?.takeIf { it.isNotBlank() }
            ?: groupFromTag
            ?: ""
        return M3uEntry(
            title = title,
            group = group.trim(),
            logo = attributes["tvg-logo"]?.takeIf { it.isNotBlank() },
            tvgId = attributes["tvg-id"]?.takeIf { it.isNotBlank() },
            streamUrl = link,
        )
    }

    /** Содержимое строки `#EXTINF`: атрибуты (ключи в нижнем регистре) и название. */
    private class InfoLine(val attributes: Map<String, String>, val title: String)

    /**
     * Разбирает текст после `#EXTINF:` вида `-1 key="value" key=value,Название`.
     * Название — всё после первой запятой, которая стоит вне кавычек.
     */
    private fun parseInfo(text: String): InfoLine {
        val attributes = HashMap<String, String>()
        var cursor = 0
        val end = text.length

        // Длительность (обычно -1) — первое слово, она нам не нужна.
        while (cursor < end && !text[cursor].isWhitespace() && text[cursor] != ',') cursor++

        while (cursor < end) {
            val ch = text[cursor]
            when {
                ch.isWhitespace() -> cursor++
                ch == ',' -> return InfoLine(attributes, text.substring(cursor + 1).trim())
                else -> {
                    val keyStart = cursor
                    while (cursor < end && text[cursor] != '=' && text[cursor] != ',' &&
                        !text[cursor].isWhitespace()
                    ) cursor++
                    val key = text.substring(keyStart, cursor).lowercase()
                    if (cursor < end && text[cursor] == '=') {
                        cursor++
                        val value: String
                        if (cursor < end && (text[cursor] == '"' || text[cursor] == '\'')) {
                            val quote = text[cursor]
                            val closing = text.indexOf(quote, cursor + 1)
                            if (closing < 0) {
                                // Кавычка не закрыта: значение до конца строки, названия нет.
                                value = text.substring(cursor + 1)
                                cursor = end
                            } else {
                                value = text.substring(cursor + 1, closing)
                                cursor = closing + 1
                            }
                        } else {
                            val valueStart = cursor
                            while (cursor < end && text[cursor] != ',' &&
                                !text[cursor].isWhitespace()
                            ) cursor++
                            value = text.substring(valueStart, cursor)
                        }
                        if (key.isNotEmpty()) attributes[key] = value.trim()
                    }
                }
            }
        }
        return InfoLine(attributes, "")
    }
}
