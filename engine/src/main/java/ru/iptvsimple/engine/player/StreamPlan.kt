package ru.iptvsimple.engine.player

import androidx.media3.common.PlaybackException
import ru.iptvsimple.engine.m3u.StreamUrl

/** Как открывать поток: как HLS-список или как обычный поток (TS и прочее). */
internal enum class StreamFormat { HLS, PROGRESSIVE }

/**
 * Что нужно знать плееру о ссылке канала: чистый адрес, User-Agent
 * и остальные HTTP-заголовки из хвоста `|…`.
 */
internal data class StreamRequest(
    val url: String,
    val userAgent: String,
    val headers: Map<String, String>,
    /** Порядок попыток: первый формат и запасной на случай ошибки разбора. */
    val formats: List<StreamFormat>,
    /** Заголовки из ссылки, которые HTTP не пропустит (не ASCII) и которые мы отбросили. */
    val dropped: List<String> = emptyList(),
)

/** Чистые правила выбора способа воспроизведения, без Android. */
internal object StreamPlan {

    /**
     * Собирает запрос к потоку. User-Agent из плейлиста важнее нашего,
     * остальные заголовки уходят как есть. Заголовок с буквами не из ASCII
     * (например, кириллицей) сетевая библиотека отвергает, и канал не открылся
     * бы никогда — такие заголовки отбрасываем, а вместо такого User-Agent берём свой.
     */
    fun request(rawUrl: String, defaultUserAgent: String): StreamRequest {
        val parsed = StreamUrl.parse(rawUrl)
        val (usable, unusable) = parsed.headers.entries.partition { (name, value) ->
            isSendable(name, value)
        }
        val headers = usable.associate { it.key to it.value }
        val agent = headers[StreamUrl.USER_AGENT]?.takeIf { it.isNotBlank() } ?: defaultUserAgent
        return StreamRequest(
            url = parsed.url,
            userAgent = agent,
            headers = headers - StreamUrl.USER_AGENT,
            formats = formatOrder(parsed.url),
            dropped = unusable.map { it.key },
        )
    }

    // Те же правила, что у OkHttp: имя — видимые символы ASCII,
    // значение — видимые ASCII, пробел и табуляция.
    private fun isSendable(name: String, value: String): Boolean =
        name.isNotEmpty() && name.all { it in '!'..'~' } &&
            value.all { it == '	' || it in ' '..'~' }

    /**
     * Если в пути ссылки есть `.m3u8` — сначала пробуем HLS, иначе сначала
     * обычный поток. Второй элемент — единственная запасная попытка.
     */
    fun formatOrder(url: String): List<StreamFormat> {
        val path = pathOf(url).lowercase()
        return if (".m3u8" in path) {
            listOf(StreamFormat.HLS, StreamFormat.PROGRESSIVE)
        } else {
            listOf(StreamFormat.PROGRESSIVE, StreamFormat.HLS)
        }
    }

    /** Ошибка значит «не тот формат контейнера» — есть смысл попробовать другой. */
    fun isFormatMismatch(errorCode: Int): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> true
        else -> false
    }

    // Путь без схемы, адреса сервера, параметров «?…» и якоря «#…».
    private fun pathOf(url: String): String {
        var rest = StreamUrl.withoutHeaders(url)
        val scheme = rest.indexOf("://")
        if (scheme >= 0) {
            rest = rest.substring(scheme + 3)
            val slash = rest.indexOf('/')
            rest = if (slash >= 0) rest.substring(slash) else ""
        }
        val cut = rest.indexOfFirst { it == '?' || it == '#' }
        return if (cut >= 0) rest.substring(0, cut) else rest
    }
}
