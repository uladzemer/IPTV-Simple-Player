package ru.iptvsimple.engine.m3u

import java.io.ByteArrayOutputStream

/**
 * Ссылка на поток и HTTP-заголовки, записанные после неё через `|`:
 * `http://host/live.m3u8|User-Agent=MyPlayer/2.0&Referer=http://site/`.
 * Значения можно записывать с %-кодами (`%20` — пробел).
 *
 * Имена заголовков приводятся к единому виду (`user-agent` → `User-Agent`),
 * поэтому регистр в плейлисте не важен.
 */
data class StreamUrl(
    val url: String,
    val headers: Map<String, String>,
) {
    val userAgent: String? get() = headers[USER_AGENT]

    companion object {
        const val USER_AGENT = "User-Agent"
        private const val REFERER = "Referer"

        fun parse(raw: String): StreamUrl {
            val text = raw.trim()
            val bar = text.indexOf('|')
            if (bar < 0) return StreamUrl(text, emptyMap())
            val headers = LinkedHashMap<String, String>()
            for (part in text.substring(bar + 1).split('&')) {
                val equals = part.indexOf('=')
                if (equals <= 0) continue
                val name = headerName(part.substring(0, equals).trim())
                if (name.isEmpty()) continue
                headers[name] = decodePercent(part.substring(equals + 1).trim())
            }
            return StreamUrl(text.substring(0, bar).trim(), headers)
        }

        /** Ссылка без хвоста с заголовками. */
        fun withoutHeaders(raw: String): String = raw.substringBefore('|').trim()

        private fun headerName(name: String): String {
            if (name.equals("referrer", ignoreCase = true)) return REFERER
            return name.split('-').joinToString("-") { word ->
                word.lowercase().replaceFirstChar { it.uppercaseChar() }
            }
        }

        /** Раскрывает `%2F` и подобные последовательности, остальное оставляет как есть. */
        private fun decodePercent(value: String): String {
            if ('%' !in value) return value
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < value.length) {
                val ch = value[i]
                val code = if (ch == '%' && i + 3 <= value.length) {
                    value.substring(i + 1, i + 3).toIntOrNull(16)
                } else {
                    null
                }
                if (code != null) {
                    bytes.write(code)
                    i += 3
                } else {
                    // Обычный текст копируем кусками до следующего «%».
                    var next = value.indexOf('%', i + 1)
                    if (next < 0) next = value.length
                    val piece = value.substring(i, next).toByteArray(Charsets.UTF_8)
                    bytes.write(piece, 0, piece.size)
                    i = next
                }
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }
}
