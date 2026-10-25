package ru.iptvsimple.engine.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import ru.iptvsimple.engine.db.ChannelDao
import ru.iptvsimple.engine.db.PlaylistDao
import ru.iptvsimple.engine.db.TvDatabase
import ru.iptvsimple.engine.net.DefaultUserAgent
import ru.iptvsimple.engine.net.NetworkSetup
import javax.inject.Singleton

/** Как Hilt собирает базу и сеть движка. */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): TvDatabase = TvDatabase.open(context)

    @Provides
    fun playlistDao(database: TvDatabase): PlaylistDao = database.playlists()

    @Provides
    fun channelDao(database: TvDatabase): ChannelDao = database.channels()

    @Provides
    @Singleton
    fun httpClient(): OkHttpClient = NetworkSetup.buildClient()

    @Provides
    @Singleton
    @DefaultUserAgent
    fun userAgent(@ApplicationContext context: Context): String = NetworkSetup.userAgent(context)
}
