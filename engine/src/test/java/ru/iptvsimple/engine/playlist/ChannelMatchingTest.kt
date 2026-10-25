package ru.iptvsimple.engine.playlist

import ru.iptvsimple.engine.db.ChannelEntity
import ru.iptvsimple.engine.db.ChannelMarks
import ru.iptvsimple.engine.playlist.ChannelMatching.carryOver
import ru.iptvsimple.engine.playlist.ChannelMatching.matchKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ChannelMatchingTest {

    private fun channel(
        title: String,
        streamUrl: String,
        group: String = "",
        tvgId: String? = null,
        position: Int = 0,
    ) = ChannelEntity(
        playlistUrl = "http://list/",
        position = position,
        title = title,
        groupName = group,
        logo = null,
        streamUrl = streamUrl,
        matchKey = matchKey(tvgId, streamUrl, group, title),
    )

    private var nextId = 100L

    private fun ChannelEntity.marked(favorite: Boolean, watchedAt: Long, rowId: Long = nextId++) =
        ChannelMarks(rowId, matchKey, favorite, watchedAt)

    @Test
    fun `ключ строится по tvg-id, затем по ссылке, затем по группе и названию`() {
        assertEquals("id:abc", matchKey("abc", "http://a/1|User-Agent=x", "Г", "Н"))
        assertEquals("url:http://a/1", matchKey(null, "http://a/1|User-Agent=x", "Г", "Н"))
        assertEquals("url:http://a/1", matchKey("  ", "http://a/1", "Г", "Н"))
        assertEquals("name:Г|Н", matchKey(null, "", "Г", "Н"))
        assertNotEquals(matchKey("x", "", "", ""), matchKey(null, "x", "", ""))
    }

    @Test
    fun `пометки переносятся по tvg-id, даже если ссылка сменилась`() {
        val old = channel("Первый", "http://old/1.m3u8", tvgId = "perviy")
        val fresh = channel("Первый HD", "http://new/1.m3u8", tvgId = "perviy")
        val result = carryOver(listOf(old.marked(true, 1000)), listOf(fresh)).single()
        assertTrue(result.favorite)
        assertEquals(1000, result.lastWatchedAt)
        assertEquals("Первый HD", result.title)
    }

    @Test
    fun `пометки переносятся по ссылке, даже если сменились название и заголовки`() {
        val old = channel("Россия", "http://a/rossiya.m3u8|User-Agent=old", group = "Общие")
        val fresh = channel("Россия 1", "http://a/rossiya.m3u8", group = "Федеральные")
        val result = carryOver(listOf(old.marked(true, 0)), listOf(fresh)).single()
        assertTrue(result.favorite)
        assertEquals(0, result.lastWatchedAt)
    }

    @Test
    fun `пометки переносятся по группе и названию, когда другого ключа нет`() {
        val old = channel("Звезда", "", group = "Кино")
        val fresh = channel("Звезда", "", group = "Кино", position = 5)
        val other = channel("Звезда", "", group = "Документальные", position = 6)
        val result = carryOver(listOf(old.marked(false, 777)), listOf(fresh, other))
        assertEquals(777, result[0].lastWatchedAt)
        assertFalse(result[0].favorite)
        assertEquals(0, result[1].lastWatchedAt)
    }

    @Test
    fun `новые каналы приходят без пометок, удалённые исчезают`() {
        val kept = channel("Остался", "http://a/kept")
        val removed = channel("Удалён", "http://a/removed")
        val added = channel("Новый", "http://a/added")
        val result = carryOver(
            listOf(kept.marked(true, 10), removed.marked(true, 20)),
            listOf(added, kept),
        )
        assertEquals(listOf("Новый", "Остался"), result.map { it.title })
        assertFalse(result[0].favorite)
        assertEquals(0, result[0].lastWatchedAt)
        assertTrue(result[1].favorite)
        assertEquals(10, result[1].lastWatchedAt)
    }

    @Test
    fun `при нескольких совпадениях берётся первое`() {
        val firstOld = channel("Дубль", "http://a/same")
        val secondOld = channel("Дубль", "http://a/same")
        val result = carryOver(
            listOf(firstOld.marked(true, 1), secondOld.marked(false, 2)),
            listOf(channel("Дубль", "http://a/same"), channel("Дубль", "http://a/same")),
        )
        assertTrue(result[0].favorite)
        assertEquals(1, result[0].lastWatchedAt)
        // Второй новый дубль пометок не получает — избранное не размножается.
        assertFalse(result[1].favorite)
        assertEquals(0, result[1].lastWatchedAt)
    }

    @Test
    fun `совпавший канал сохраняет id строки, новый получает id от базы`() {
        val kept = channel("Остался", "http://a/kept")
        val added = channel("Новый", "http://a/added")
        val result = carryOver(
            listOf(kept.marked(favorite = false, watchedAt = 0, rowId = 42)),
            listOf(added, kept.copy(position = 1)),
        )
        assertEquals(0, result[0].id)
        assertEquals(42, result[1].id)
        assertEquals(1, result[1].position)
    }
}
