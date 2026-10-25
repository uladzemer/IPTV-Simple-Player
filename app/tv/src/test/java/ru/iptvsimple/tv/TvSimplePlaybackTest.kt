package ru.iptvsimple.tv

import ru.iptvsimple.engine.channel.Channel
import ru.iptvsimple.engine.settings.StartupMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvSimplePlaybackTest {
    // Номер канала — его место в плейлисте; id в тестах совпадает с номером.
    private fun channel(number: Int, group: String = "", playlist: String = "p1") =
        Channel(
            id = number.toLong(),
            playlistUrl = playlist,
            number = number,
            title = "Channel $number",
            group = group,
            logo = null,
            streamUrl = "http://example.com/$number",
            favorite = false,
            lastWatchedAt = 0,
        )

    private val ordered = listOf(channel(1), channel(2), channel(3))

    @Test
    fun `channel numbers follow the whole playlist, not the group`() {
        val numbering = TvChannelNumbering("p1", ordered)
        assertEquals(1, numbering.numberOf(channel(1)))
        assertEquals(3, numbering.numberOf(channel(3)))
        assertEquals(2L, numbering.channelAt(2)?.id)
        assertNull(numbering.channelAt(4))
        assertNull(numbering.numberOf(channel(1, playlist = "p2")))
        assertNull(TvChannelNumbering.EMPTY.numberOf(channel(1)))
    }

    @Test
    fun `zap index wraps around both ends`() {
        assertEquals(1, tvZapIndex(current = 0, size = 3, offset = 1))
        assertEquals(0, tvZapIndex(current = 2, size = 3, offset = 1))
        assertEquals(2, tvZapIndex(current = 0, size = 3, offset = -1))
        assertEquals(-1, tvZapIndex(current = 0, size = 0, offset = 1))
    }

    @Test
    fun `startup picks the requested channel number and clamps it`() {
        fun select(number: Int) = tvStartupSelection(
            mode = StartupMode.CHANNEL_NUMBER,
            channelNumber = number,
            recent = null,
            favorites = emptyList(),
            playlistChannels = { ordered },
            firstPlaylistUrl = "p1",
        )?.channel?.number
        assertEquals(2, select(2))
        assertEquals(3, select(7))
        assertEquals(1, select(0))
    }

    @Test
    fun `startup falls back to first channel when preferred source is empty`() {
        val lastPlayed = tvStartupSelection(
            mode = StartupMode.LAST_WATCHED,
            channelNumber = 1,
            recent = null,
            favorites = emptyList(),
            playlistChannels = { ordered },
            firstPlaylistUrl = "p1",
        )
        assertEquals(1, lastPlayed?.channel?.number)
        val favorite = tvStartupSelection(
            mode = StartupMode.FIRST_FAVORITE,
            channelNumber = 1,
            recent = null,
            favorites = listOf(channel(2)),
            playlistChannels = { ordered },
            firstPlaylistUrl = "p1",
        )
        assertEquals(2, favorite?.channel?.number)
        assertEquals(listOf(2), favorite?.queue?.map { it.number })
    }

    @Test
    fun `startup is disabled in off mode`() {
        assertNull(
            tvStartupSelection(
                mode = StartupMode.OFF,
                channelNumber = 1,
                recent = channel(1),
                favorites = emptyList(),
                playlistChannels = { ordered },
                firstPlaylistUrl = "p1",
            )
        )
    }

    @Test
    fun `groups list favorites, all channels and categories in appearance order`() {
        val channels = listOf(channel(1, "News"), channel(2, "Sport"), channel(3, "News"), channel(4))
        val groups = tvChannelGroups(channels, listOf(channel(2)), "Fav", "All", "Other")
        assertEquals(listOf("Fav", "All", "News", "Sport", "Other"), groups.map { it.title })
        assertEquals(listOf(1, 3), groups[2].channels.map { it.number })
    }

    @Test
    fun `single category playlist only shows all channels`() {
        val groups = tvChannelGroups(listOf(channel(1), channel(2)), emptyList(), "Fav", "All", "Other")
        assertEquals(listOf("All"), groups.map { it.title })
    }
}
