package ru.iptvsimple.engine.m3u

/**
 * Одна запись плейлиста в том виде, в каком она лежит в файле.
 *
 * [streamUrl] хранится целиком, вместе с хвостом `|Заголовок=…`, чтобы плеер
 * мог достать из него HTTP-заголовки через [StreamUrl.parse].
 * [group] — пустая строка, если группа не указана.
 */
data class M3uEntry(
    val title: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    val streamUrl: String,
)
