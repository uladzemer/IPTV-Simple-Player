package ru.iptvsimple.engine.channel

import ru.iptvsimple.engine.db.ChannelEntity

/**
 * Канал в том виде, в каком его видит интерфейс.
 *
 * [number] — постоянный номер канала: его место в плейлисте, начиная с 1.
 * Он не зависит от выбранной группы, поэтому цифры на пульте всегда ведут
 * на один и тот же канал.
 */
data class Channel(
    val id: Long,
    val playlistUrl: String,
    val number: Int,
    val title: String,
    val group: String,
    val logo: String?,
    val streamUrl: String,
    val favorite: Boolean,
    val lastWatchedAt: Long,
)

internal fun ChannelEntity.toChannel(): Channel = Channel(
    id = id,
    playlistUrl = playlistUrl,
    number = position + 1,
    title = title,
    group = groupName,
    logo = logo,
    streamUrl = streamUrl,
    favorite = favorite,
    lastWatchedAt = lastWatchedAt,
)
