package ru.iptvsimple.engine.playlist

import ru.iptvsimple.engine.db.ChannelEntity
import ru.iptvsimple.engine.db.ChannelMarks
import ru.iptvsimple.engine.m3u.StreamUrl

/**
 * Как узнать «тот же самый» канал после обновления плейлиста.
 *
 * Чистые функции без базы и сети, поэтому проверяются обычными JVM-тестами.
 */
object ChannelMatching {

    /**
     * Ключ канала: tvg-id, если он есть; иначе ссылка без заголовков;
     * иначе пара «группа|название». Приставки не дают ключам разных видов совпасть.
     */
    fun matchKey(tvgId: String?, streamUrl: String, group: String, title: String): String {
        val id = tvgId?.trim().orEmpty()
        if (id.isNotEmpty()) return "id:$id"
        val link = StreamUrl.withoutHeaders(streamUrl)
        if (link.isNotEmpty()) return "url:$link"
        return "name:${group.trim()}|${title.trim()}"
    }

    /**
     * Переносит id, избранное и время просмотра со старых каналов на новые.
     *
     * id сохраняется, чтобы после обновления плейлиста играющий канал, очередь
     * листания и избранное продолжали указывать на ту же строку базы.
     * [previous] — старые каналы в порядке плейлиста. Если у нескольких
     * старых каналов один ключ, берётся первый. Если у нескольких новых каналов
     * один ключ, пометки получает только первый из них — иначе один любимый канал
     * размножился бы в избранном. Каналы, которых больше нет в файле, просто
     * исчезают вместе со своими пометками; новые каналы приходят без пометок.
     */
    fun carryOver(previous: List<ChannelMarks>, fresh: List<ChannelEntity>): List<ChannelEntity> {
        if (previous.isEmpty()) return fresh
        val waiting = HashMap<String, ChannelMarks>(previous.size)
        for (marks in previous) waiting.putIfAbsent(marks.matchKey, marks)
        return fresh.map { channel ->
            val marks = waiting.remove(channel.matchKey) ?: return@map channel
            channel.copy(id = marks.id, favorite = marks.favorite, lastWatchedAt = marks.lastWatchedAt)
        }
    }
}
