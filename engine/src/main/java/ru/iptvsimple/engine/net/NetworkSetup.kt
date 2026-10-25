package ru.iptvsimple.engine.net

import android.content.Context
import android.content.pm.PackageManager
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Сетевые настройки движка: один HTTP-клиент на всё приложение. */
object NetworkSetup {
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 30L

    /** Обычная проверка сертификатов, без исключений «доверять всем». */
    fun buildClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Клиент для видео, свой на каждый запуск канала ([PlaybackConnections]):
     * свои очередь и пул соединений, чтобы обрыв соединений канала не задел
     * загрузку M3U-плейлистов.
     *
     * Таймауты короче обычных: кусок, который сервер не отдаёт 10 с, быстрее
     * перезапросить, чем ждать.
     */
    fun buildPlaybackClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .dispatcher(Dispatcher())
        .connectionPool(ConnectionPool(PLAYBACK_IDLE_CONNECTIONS, PLAYBACK_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
        .connectTimeout(PLAYBACK_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(PLAYBACK_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    const val PLAYBACK_CONNECT_TIMEOUT_SECONDS = 8L
    const val PLAYBACK_READ_TIMEOUT_SECONDS = 10L
    private const val PLAYBACK_IDLE_CONNECTIONS = 2
    private const val PLAYBACK_KEEP_ALIVE_SECONDS = 30L

    /** User-Agent по умолчанию: «SimpleTV/<версия приложения>». */
    fun userAgent(context: Context): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        return "SimpleTV/${version ?: "0"}"
    }
}
