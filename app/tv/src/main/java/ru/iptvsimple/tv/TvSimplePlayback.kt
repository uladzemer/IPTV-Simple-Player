package ru.iptvsimple.tv

import ru.iptvsimple.engine.channel.Channel
import ru.iptvsimple.engine.settings.StartupMode

internal enum class TvChannelGroupKind {
    FAVORITES,
    ALL,
    CATEGORY,
}

internal data class TvChannelGroup(
    val kind: TvChannelGroupKind,
    val title: String,
    val channels: List<Channel>,
) {
    val key: String get() = "${kind.name}:$title"
}

/**
 * Постоянная нумерация каналов одного плейлиста. Номер — это место канала
 * в файле плейлиста ([Channel.number]), он не зависит от группы, по которой
 * сейчас листают. Каналы других плейлистов здесь номера не получают.
 */
class TvChannelNumbering(
    val playlistUrl: String?,
    val channels: List<Channel>,
) {
    private val byNumber: Map<Int, Channel> = channels.associateBy { it.number }

    fun numberOf(channel: Channel): Int? =
        channel.number.takeIf { channel.playlistUrl == playlistUrl }

    /** Канал с номером [number], набранным цифрами на пульте. */
    fun channelAt(number: Int): Channel? = byNumber[number]

    companion object {
        val EMPTY = TvChannelNumbering(null, emptyList())
    }
}

internal data class TvStartupSelection(
    val channel: Channel,
    val queue: List<Channel>,
)

/**
 * Выбирает канал для автозапуска. Если выбранный источник пуст, берёт
 * первый канал первого плейлиста.
 */
internal fun tvStartupSelection(
    mode: StartupMode,
    channelNumber: Int,
    recent: Channel?,
    favorites: List<Channel>,
    playlistChannels: (playlistUrl: String) -> List<Channel>,
    firstPlaylistUrl: String?,
): TvStartupSelection? {
    if (mode == StartupMode.OFF) return null
    val firstPlaylist = firstPlaylistUrl?.let(playlistChannels).orEmpty()
    when (mode) {
        StartupMode.LAST_WATCHED -> if (recent != null) {
            val queue = playlistChannels(recent.playlistUrl)
            return TvStartupSelection(recent, queue.ifEmpty { listOf(recent) })
        }

        StartupMode.FIRST_FAVORITE -> favorites.firstOrNull()?.let { favorite ->
            return TvStartupSelection(favorite, favorites)
        }

        StartupMode.CHANNEL_NUMBER -> {
            val index = (channelNumber - 1).coerceIn(0, (firstPlaylist.size - 1).coerceAtLeast(0))
            firstPlaylist.getOrNull(index)?.let { channel ->
                return TvStartupSelection(channel, firstPlaylist)
            }
        }

        StartupMode.OFF -> Unit
    }
    return firstPlaylist.firstOrNull()?.let { TvStartupSelection(it, firstPlaylist) }
}

/**
 * Индекс после сдвига на [offset] шагов от [current] по кругу в списке
 * из [size] элементов: с конца переходим в начало и наоборот.
 */
internal fun tvZapIndex(current: Int, size: Int, offset: Int): Int {
    if (size <= 0) return -1
    val start = current.coerceIn(0, size - 1)
    return Math.floorMod(start + offset, size)
}

/**
 * Папки для панели каналов: избранное, все каналы, затем группы в том
 * порядке, в каком они впервые встречаются в плейлисте.
 */
internal fun tvChannelGroups(
    channels: List<Channel>,
    favorites: List<Channel>,
    favoritesTitle: String,
    allTitle: String,
    uncategorizedTitle: String,
): List<TvChannelGroup> = buildList {
    if (favorites.isNotEmpty()) {
        add(TvChannelGroup(TvChannelGroupKind.FAVORITES, favoritesTitle, favorites))
    }
    if (channels.isNotEmpty()) {
        add(TvChannelGroup(TvChannelGroupKind.ALL, allTitle, channels))
    }
    val categories = channels.groupBy { it.group.trim() }
    if (categories.size > 1) {
        categories.forEach { (category, items) ->
            add(
                TvChannelGroup(
                    kind = TvChannelGroupKind.CATEGORY,
                    title = category.ifEmpty { uncategorizedTitle },
                    channels = items,
                )
            )
        }
    }
}

internal enum class TvStallReason {
    /** Канал так и не начал играть. */
    NO_START,
    /** Картинка шла, потом слишком долго грузится. */
    STUCK_BUFFERING,
    /** Плеер «играет», но позиция стоит: картинка застыла. */
    FROZEN,
    /** Живой эфир сообщил, что закончился. */
    ENDED,
}

/**
 * Замечает каналы, которые зависли без ошибки: не стартовали, бесконечно
 * грузятся или застыли. Раз в пару секунд получает состояние плеера и
 * возвращает причину, если канал пора перезапустить.
 */
internal class TvStallDetector(
    private val startTimeoutMillis: Long = 15_000,
    private val bufferingTimeoutMillis: Long = 8_000,
    private val frozenTimeoutMillis: Long = 8_000,
) {
    private var startedAt = 0L
    private var everReady = false
    private var bufferingSince: Long? = null
    private var lastPosition = Long.MIN_VALUE
    private var positionSince = 0L

    fun reset(now: Long) {
        startedAt = now
        everReady = false
        bufferingSince = null
        lastPosition = Long.MIN_VALUE
        positionSince = now
    }

    /**
     * После мягкого восстановления того же плеера: отсчёт долгой загрузки
     * начинается заново, а то, что канал уже шёл, не забывается — иначе
     * следующая остановка ждала бы долгий таймаут старта.
     */
    fun restartBufferingTimer(now: Long) {
        if (bufferingSince != null) bufferingSince = now
    }

    /** [state] — значение Player.STATE_* из media3. */
    fun check(now: Long, state: Int, playWhenReady: Boolean, position: Long): TvStallReason? {
        when (state) {
            STATE_READY -> {
                everReady = true
                bufferingSince = null
                if (!playWhenReady || position != lastPosition) {
                    lastPosition = position
                    positionSince = now
                } else if (now - positionSince >= frozenTimeoutMillis) {
                    return TvStallReason.FROZEN
                }
            }

            STATE_ENDED -> return TvStallReason.ENDED

            else -> {
                positionSince = now
                lastPosition = Long.MIN_VALUE
                if (!everReady) {
                    if (now - startedAt >= startTimeoutMillis) return TvStallReason.NO_START
                } else {
                    val since = bufferingSince ?: now.also { bufferingSince = it }
                    if (now - since >= bufferingTimeoutMillis) return TvStallReason.STUCK_BUFFERING
                }
            }
        }
        return null
    }

    private companion object {
        // Те же числа, что Player.STATE_* в media3. Свои копии нужны, чтобы
        // сторож оставался чистым Kotlin и проверялся обычными тестами.
        const val STATE_READY = 3
        const val STATE_ENDED = 4
    }
}

/** Короткое имя видеокодека для плашки канала или null, если кодек редкий. */
internal fun tvVideoCodecName(mimeType: String?): String? = when (mimeType) {
    "video/avc" -> "H.264"
    "video/hevc" -> "H.265"
    "video/mpeg2" -> "MPEG-2"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    else -> null
}

/** Частота кадров без лишних нулей: 25, 29.97, 23.98; null, если поток её не сообщил. */
internal fun tvFrameRateText(frameRate: Float): String? {
    if (!(frameRate > 0f) || frameRate.isInfinite()) return null
    val hundredths = Math.round(frameRate * 100.0)
    val whole = hundredths / 100
    val rest = hundredths % 100
    return when {
        rest == 0L -> whole.toString()
        rest % 10 == 0L -> "$whole.${rest / 10}"
        else -> "$whole.${rest.toString().padStart(2, '0')}"
    }
}
