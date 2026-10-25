package ru.iptvsimple.engine.channel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.iptvsimple.engine.db.ChannelDao
import javax.inject.Inject
import javax.inject.Singleton

/** Каналы, избранное и последний просмотренный канал. */
@Singleton
class ChannelRepository @Inject constructor(
    private val dao: ChannelDao,
) {
    /** Все каналы плейлиста в порядке файла. */
    suspend fun byPlaylist(playlistUrl: String): List<Channel> =
        dao.byPlaylist(playlistUrl).map { it.toChannel() }

    suspend fun get(id: Long): Channel? = dao.find(id)?.toChannel()

    // Room сообщает о любом изменении таблицы, поэтому одинаковые списки отсеиваем.
    fun observeFavorites(): Flow<List<Channel>> =
        dao.observeFavorites().map { list -> list.map { it.toChannel() } }
            .distinctUntilChanged()

    /** Добавляет канал в избранное или убирает из него. Возвращает новое состояние. */
    suspend fun toggleFavorite(id: Long): Boolean? {
        dao.flipFavorite(id)
        return dao.find(id)?.favorite
    }

    /** Запоминает, что канал только что запустили. */
    suspend fun markWatched(id: Long, time: Long = System.currentTimeMillis()) {
        dao.setLastWatched(id, time)
    }

    suspend fun lastWatched(): Channel? = dao.lastWatched()?.toChannel()

    fun observeLastWatched(): Flow<Channel?> =
        dao.observeLastWatched().map { it?.toChannel() }.distinctUntilChanged()
}
