package ru.iptvsimple.engine.playlist

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.iptvsimple.engine.db.ChannelEntity
import ru.iptvsimple.engine.db.PlaylistEntity
import ru.iptvsimple.engine.db.TvDatabase
import ru.iptvsimple.engine.m3u.M3uEntry
import ru.iptvsimple.engine.m3u.M3uReader
import ru.iptvsimple.engine.net.DefaultUserAgent
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Скачивает плейлист, разбирает его и записывает каналы в базу.
 *
 * Файл читается потоком прямо из сети. Каналы собираются в память (даже
 * десятки тысяч коротких записей — это немного), и только потом одной
 * транзакцией заменяют старые: если загрузка оборвалась, старый список
 * каналов остаётся целым.
 */
@Singleton
class PlaylistImporter @Inject constructor(
    private val database: TvDatabase,
    private val client: OkHttpClient,
    @param:DefaultUserAgent private val userAgent: String,
) {
    /** Не даём двум загрузкам одновременно переписывать базу. */
    private val writeLock = Mutex()

    /**
     * Загружает плейлист [url]. У каналов, которые уже были в этом плейлисте,
     * сохраняются id, избранное и время просмотра. [onProgress] получает число
     * прочитанных каналов.
     */
    suspend fun import(url: String, onProgress: (Int) -> Unit = {}): ImportResult =
        withContext(Dispatchers.IO) {
            val key = url.trim()
            val link = parseLink(key) ?: return@withContext ImportResult.Failure(
                key, ImportResult.Reason.INVALID_URL,
            )
            val entries = try {
                download(link, onProgress)
            } catch (error: ServerRefused) {
                return@withContext ImportResult.Failure(key, ImportResult.Reason.HTTP, error.code)
            } catch (_: IOException) {
                return@withContext ImportResult.Failure(key, ImportResult.Reason.NETWORK)
            }
            if (entries.isEmpty()) {
                return@withContext ImportResult.Failure(key, ImportResult.Reason.EMPTY)
            }
            writeLock.withLock { store(key, link, entries) }
            ImportResult.Success(key, entries.size)
        }

    private suspend fun download(link: HttpUrl, onProgress: (Int) -> Unit): List<M3uEntry> {
        val request = Request.Builder()
            .url(link)
            .header("User-Agent", userAgent)
            .build()
        val call = client.newCall(request)
        // Если загрузку отменили (например, закрыли экран), обрываем и сетевой запрос.
        val cancelHook = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw ServerRefused(response.code)
                val body = response.body ?: throw IOException("пустой ответ сервера")
                val entries = ArrayList<M3uEntry>()
                body.byteStream().use { stream ->
                    for (entry in M3uReader.read(stream)) {
                        entries += entry
                        if (entries.size % PROGRESS_STEP == 0) {
                            currentCoroutineContext().ensureActive()
                            onProgress(entries.size)
                        }
                    }
                }
                onProgress(entries.size)
                return entries
            }
        } finally {
            cancelHook?.dispose()
        }
    }

    private suspend fun store(key: String, link: HttpUrl, entries: List<M3uEntry>) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val playlists = database.playlists()
            val channels = database.channels()
            val known = playlists.find(key)
            val previousMarks = if (known != null) channels.marks(key) else emptyList()
            playlists.save(
                PlaylistEntity(
                    url = key,
                    title = known?.title ?: link.host,
                    addedAt = known?.addedAt ?: now,
                    updatedAt = now,
                )
            )
            val fresh = ChannelMatching.carryOver(
                previousMarks,
                entries.mapIndexed { index, entry -> entry.toEntity(key, index) },
            )
            // Совпавшие каналы обновляем на месте (id не меняется), пропавшие
            // удаляем, новые добавляем. Пакетами — у SQLite есть предел числа параметров.
            val (kept, added) = fresh.partition { it.id != 0L }
            val keptIds = kept.mapTo(HashSet(kept.size)) { it.id }
            previousMarks.map { it.id }.filterNot { it in keptIds }
                .chunked(BATCH)
                .forEach { ids -> channels.deleteByIds(ids) }
            kept.chunked(BATCH).forEach { batch -> channels.update(batch) }
            added.chunked(BATCH).forEach { batch -> channels.insert(batch) }
        }
    }

    private class ServerRefused(val code: Int) : IOException("сервер ответил $code")

    companion object {
        private const val PROGRESS_STEP = 100
        private const val BATCH = 500

        /** Принимаем только http и https. */
        fun parseLink(url: String): HttpUrl? =
            url.trim().toHttpUrlOrNull()?.takeIf { it.scheme == "http" || it.scheme == "https" }

        internal fun M3uEntry.toEntity(playlistUrl: String, position: Int) = ChannelEntity(
            playlistUrl = playlistUrl,
            position = position,
            title = title,
            groupName = group,
            logo = logo,
            streamUrl = streamUrl,
            matchKey = ChannelMatching.matchKey(tvgId, streamUrl, group, title),
        )
    }
}
