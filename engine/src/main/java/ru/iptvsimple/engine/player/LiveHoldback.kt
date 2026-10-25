package ru.iptvsimple.engine.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.InputStream

/**
 * Не берём самые свежие кусочки живого эфира.
 *
 * Часть CDN объявляет кусок в плейлисте раньше, чем готова его отдать.
 * Если попросить его сразу, запрос висит до таймаута (на приставке так
 * «умирали» американские каналы через минуту-две после включения). Поэтому
 * последние [segments] кусков из списка убираются: плеер возьмёт их при
 * следующем обновлении плейлиста, когда за ними уже появятся новые.
 *
 * Не трогаем плейлист с концом (#EXT-X-ENDLIST), LL-HLS (#EXT-X-PART) и
 * блокирующую перезагрузку (CAN-BLOCK-RELOAD): плеер там сразу просит
 * следующий кусок, и обрезка зациклила бы запросы плейлиста.
 */
object LiveHoldback {

    /** Сколько кусков держать в запасе у края эфира. */
    const val SEGMENTS = 1

    /** Сколько кусков должно остаться после обрезки, иначе не режем. */
    private const val MIN_LEFT = 3

    private const val SEGMENT_TAG = "#EXTINF"

    private val UNTOUCHED = listOf("#EXT-X-ENDLIST", "#EXT-X-PART:", "CAN-BLOCK-RELOAD=YES")

    /** Убирает [segments] последних кусков из текста живого плейлиста. */
    fun trim(playlist: String, segments: Int = SEGMENTS): String {
        if (segments <= 0) return playlist
        if (UNTOUCHED.any { it in playlist }) return playlist
        val lines = playlist.lines()
        val starts = lines.indices.filter { lines[it].startsWith(SEGMENT_TAG) }
        if (starts.size - segments < MIN_LEFT) return playlist
        // Режем сразу после ссылки последнего оставляемого куска: служебные
        // строки перед убранными кусками уходят вместе с ними.
        val keptStart = starts[starts.size - segments - 1]
        val keptUri = (keptStart + 1 until lines.size).firstOrNull { isUri(lines[it]) }
            ?: return playlist
        return lines.subList(0, keptUri + 1).joinToString("\n", postfix = "\n")
    }

    private fun isUri(line: String): Boolean {
        val text = line.trim()
        return text.isNotEmpty() && !text.startsWith("#")
    }

    /** Разбор плейлистов HLS с обрезкой края живого эфира. */
    @OptIn(UnstableApi::class)
    class ParserFactory : HlsPlaylistParserFactory {
        private val default = DefaultHlsPlaylistParserFactory()

        override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
            trimming(default.createPlaylistParser())

        override fun createPlaylistParser(
            multivariantPlaylist: HlsMultivariantPlaylist,
            previousMediaPlaylist: HlsMediaPlaylist?,
        ): ParsingLoadable.Parser<HlsPlaylist> =
            trimming(default.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

        private fun trimming(parser: ParsingLoadable.Parser<HlsPlaylist>) =
            ParsingLoadable.Parser { uri: Uri, input: InputStream ->
                val text = input.bufferedReader(Charsets.UTF_8).readText()
                parser.parse(uri, trim(text).byteInputStream(Charsets.UTF_8))
            }
    }
}
