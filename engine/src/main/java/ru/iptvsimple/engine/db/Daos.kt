package ru.iptvsimple.engine.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Query(
        """
        SELECT p.url, p.title, p.added_at, p.updated_at,
               (SELECT COUNT(*) FROM channels c WHERE c.playlist_url = p.url) AS channel_count
        FROM playlists p
        ORDER BY p.added_at, p.url
        """
    )
    fun observeWithCounts(): Flow<List<PlaylistWithCount>>

    @Query("SELECT * FROM playlists ORDER BY added_at, url")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE url = :url")
    suspend fun find(url: String): PlaylistEntity?

    @Upsert
    suspend fun save(playlist: PlaylistEntity)

    /** Каналы удаляются вместе с плейлистом (внешний ключ с CASCADE). */
    @Query("DELETE FROM playlists WHERE url = :url")
    suspend fun delete(url: String)
}

@Dao
interface ChannelDao {

    @Insert
    suspend fun insert(channels: List<ChannelEntity>)

    /** Переписывает уже существующие строки (по id) свежими данными из плейлиста. */
    @Update
    suspend fun update(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM channels WHERE playlist_url = :playlistUrl ORDER BY position")
    suspend fun byPlaylist(playlistUrl: String): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :id")
    suspend fun find(id: Long): ChannelEntity?

    @Query(
        """
        SELECT c.* FROM channels c JOIN playlists p ON p.url = c.playlist_url
        WHERE c.favorite = 1
        ORDER BY p.added_at, p.url, c.position
        """
    )
    fun observeFavorites(): Flow<List<ChannelEntity>>

    @Query("UPDATE channels SET favorite = NOT favorite WHERE id = :id")
    suspend fun flipFavorite(id: Long)

    @Query("UPDATE channels SET last_watched_at = :time WHERE id = :id")
    suspend fun setLastWatched(id: Long, time: Long)

    @Query("SELECT * FROM channels WHERE last_watched_at > 0 ORDER BY last_watched_at DESC LIMIT 1")
    suspend fun lastWatched(): ChannelEntity?

    @Query("SELECT * FROM channels WHERE last_watched_at > 0 ORDER BY last_watched_at DESC LIMIT 1")
    fun observeLastWatched(): Flow<ChannelEntity?>

    /** Все каналы плейлиста в порядке файла: id, ключ и пометки зрителя. */
    @Query(
        """
        SELECT id, match_key, favorite, last_watched_at FROM channels
        WHERE playlist_url = :playlistUrl
        ORDER BY position
        """
    )
    suspend fun marks(playlistUrl: String): List<ChannelMarks>
}
