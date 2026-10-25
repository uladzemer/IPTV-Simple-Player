package ru.iptvsimple.engine.playlist

import kotlinx.coroutines.flow.Flow
import ru.iptvsimple.engine.db.PlaylistDao
import ru.iptvsimple.engine.db.PlaylistEntity
import ru.iptvsimple.engine.db.PlaylistWithCount
import javax.inject.Inject
import javax.inject.Singleton

/** Список плейлистов: добавить, обновить, удалить, посмотреть. */
@Singleton
class PlaylistRepository @Inject constructor(
    private val importer: PlaylistImporter,
    private val dao: PlaylistDao,
) {
    /** Добавляет плейлист по ссылке http/https. Название — адрес сервера. */
    suspend fun add(url: String, onProgress: (Int) -> Unit = {}): ImportResult =
        importer.import(url, onProgress)

    /** Перечитывает плейлист, сохраняя избранное и последний просмотр. */
    suspend fun refresh(url: String, onProgress: (Int) -> Unit = {}): ImportResult =
        importer.import(url, onProgress)

    /** Перечитывает все плейлисты по очереди. */
    suspend fun refreshAll(): List<ImportResult> = dao.all().map { importer.import(it.url) }

    fun observePlaylists(): Flow<List<PlaylistWithCount>> = dao.observeWithCounts()

    suspend fun playlists(): List<PlaylistEntity> = dao.all()

    /** Удаляет плейлист вместе со всеми его каналами. */
    suspend fun delete(url: String) = dao.delete(url)
}
