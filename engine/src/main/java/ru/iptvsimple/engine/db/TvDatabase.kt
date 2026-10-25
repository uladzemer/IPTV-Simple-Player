package ru.iptvsimple.engine.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * База каналов «Просто ТВ».
 *
 * Схема каждой версии сохраняется в `engine/schemas`. При изменении схемы
 * повышаем версию и пишем миграцию — данные зрителя (избранное) не стираем.
 */
@Database(
    entities = [PlaylistEntity::class, ChannelEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TvDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao

    companion object {
        const val FILE_NAME = "tv.db"

        /** Файл базы прежних версий приложения, он больше не читается. */
        private const val OBSOLETE_FILE_NAME = "iptvsimple.db"

        fun open(context: Context): TvDatabase {
            val appContext = context.applicationContext
            return Room.databaseBuilder(appContext, TvDatabase::class.java, FILE_NAME)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Новая база создаётся один раз — заодно убираем старую.
                        appContext.deleteDatabase(OBSOLETE_FILE_NAME)
                    }
                })
                .build()
        }
    }
}
