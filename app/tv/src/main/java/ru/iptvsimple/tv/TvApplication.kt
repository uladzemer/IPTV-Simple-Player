package ru.iptvsimple.tv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import ru.iptvsimple.engine.work.schedulePlaylistUpdates
import javax.inject.Inject

/**
 * Процесс приложения «Просто ТВ».
 *
 * Фоновое обновление плейлистов собирается через Hilt, поэтому WorkManager
 * получает фабрику отсюда. Его автоматический запуск выключен в манифесте.
 */
@HiltAndroidApp
class TvApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var hiltWorkers: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(hiltWorkers).build()

    override fun onCreate() {
        super.onCreate()
        // Раз в сутки плейлисты тихо перечитываются, и новые каналы появляются сами.
        schedulePlaylistUpdates(this)
    }
}
