package ru.iptvsimple.engine.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import ru.iptvsimple.engine.playlist.ImportResult
import ru.iptvsimple.engine.playlist.PlaylistRepository
import java.util.concurrent.TimeUnit

/**
 * Раз в сутки тихо перечитывает все плейлисты, чтобы новые каналы появлялись
 * сами. Уведомлений не показывает.
 */
@HiltWorker
class PlaylistUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val playlists: PlaylistRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val results = playlists.refreshAll()
        // Повторяем позже, только если всё сорвалось из-за сети.
        val allNetworkFailures = results.isNotEmpty() && results.all {
            it is ImportResult.Failure && it.reason == ImportResult.Reason.NETWORK
        }
        return if (allNetworkFailures) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "playlist-daily-update"
        const val INTERVAL_HOURS = 24L
    }
}

/** Ставит ежедневное обновление плейлистов. Уже поставленное не трогает. */
fun schedulePlaylistUpdates(context: Context) {
    val request = PeriodicWorkRequestBuilder<PlaylistUpdateWorker>(
        PlaylistUpdateWorker.INTERVAL_HOURS, TimeUnit.HOURS,
    )
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        PlaylistUpdateWorker.UNIQUE_NAME,
        ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
}
