package ru.iptvsimple.engine.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Плейлист, добавленный по ссылке. Ссылка служит его ключом. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Канал плейлиста.
 *
 * [position] — место канала в файле (с нуля), по нему строится постоянный номер.
 * [matchKey] — ключ, по которому при обновлении плейлиста находим тот же канал
 * и переносим на него избранное и время просмотра.
 * [lastWatchedAt] — время последнего запуска в миллисекундах, 0 — ни разу.
 */
@Entity(
    tableName = "channels",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["url"],
            childColumns = ["playlist_url"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("playlist_url"),
        Index("match_key"),
    ],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "playlist_url") val playlistUrl: String,
    @ColumnInfo(name = "position") val position: Int,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "group_name") val groupName: String,
    @ColumnInfo(name = "logo") val logo: String?,
    @ColumnInfo(name = "stream_url") val streamUrl: String,
    @ColumnInfo(name = "match_key") val matchKey: String,
    @ColumnInfo(name = "favorite") val favorite: Boolean = false,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long = 0,
)

/** Плейлист вместе с числом его каналов — для экрана настроек. */
data class PlaylistWithCount(
    @ColumnInfo(name = "url") val url: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "channel_count") val channelCount: Int,
)

/**
 * Что нужно пережить обновлению плейлиста: номер строки в базе (чтобы
 * открытые экраны и плеер продолжали ссылаться на тот же канал) и пометки зрителя.
 */
data class ChannelMarks(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "match_key") val matchKey: String,
    @ColumnInfo(name = "favorite") val favorite: Boolean,
    @ColumnInfo(name = "last_watched_at") val lastWatchedAt: Long,
)
