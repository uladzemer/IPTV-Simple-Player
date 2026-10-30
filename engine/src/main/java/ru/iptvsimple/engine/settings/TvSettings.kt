package ru.iptvsimple.engine.settings

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** С какого канала начинать при запуске приложения. */
enum class StartupMode(internal val code: Int) {
    /** Ничего не включать, показать список. */
    OFF(0),

    /** Канал, который смотрели последним. */
    LAST_WATCHED(1),

    /** Первый канал из избранного. */
    FIRST_FAVORITE(2),

    /** Канал с заданным номером. */
    CHANNEL_NUMBER(3);

    companion object {
        val DEFAULT = LAST_WATCHED

        internal fun fromCode(code: Int?): StartupMode =
            entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}

/**
 * Пропорции картинки канала. Старые каналы часто снимали в 4:3, а передают
 * растянутыми на 16:9 — для них картинку можно сузить.
 */
enum class ChannelAspect(internal val code: Int, val ratio: Float?) {
    /** Как присылает поток: на весь экран. */
    AUTO(0, null),
    CLASSIC(1, 4f / 3f),
    FILM(2, 3f / 2f);

    /** Следующий вариант по кругу: так пропорции меняются одной кнопкой «ОК». */
    fun next(): ChannelAspect = entries[(ordinal + 1) % entries.size]

    companion object {
        internal fun fromCode(code: Int?): ChannelAspect? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Пропорции каналов хранятся одной строкой «id:код,id:код». id канала
 * переживает обновление плейлиста и не переиспользуется базой, поэтому
 * настройка остаётся за тем же каналом. [AUTO] не записывается.
 */
internal fun encodeChannelAspects(aspects: Map<Long, ChannelAspect>): String =
    aspects.entries
        .filter { it.value != ChannelAspect.AUTO }
        .sortedBy { it.key }
        .joinToString(",") { "${it.key}:${it.value.code}" }

/** Обратное к [encodeChannelAspects]; испорченные части пропускаются. */
internal fun decodeChannelAspects(text: String?): Map<Long, ChannelAspect> {
    if (text.isNullOrBlank()) return emptyMap()
    return text.split(',').mapNotNull { part ->
        val id = part.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
        val aspect = ChannelAspect.fromCode(part.substringAfter(':', "").toIntOrNull())
        aspect?.takeIf { it != ChannelAspect.AUTO }?.let { id to it }
    }.toMap()
}

/** Все настройки разом. */
data class TvPreferences(
    val startupMode: StartupMode = StartupMode.DEFAULT,
    val startupChannelNumber: Int = DEFAULT_STARTUP_CHANNEL_NUMBER,
    val startOnBoot: Boolean = DEFAULT_START_ON_BOOT,
    val invertZapping: Boolean = DEFAULT_INVERT_ZAPPING,
    /** На плашке канала видно разрешение, частоту кадров и кодек потока. */
    val showStreamInfo: Boolean = DEFAULT_SHOW_STREAM_INFO,
    /** Каналы, у которых пропорции картинки заданы вручную. */
    val channelAspects: Map<Long, ChannelAspect> = emptyMap(),
) {
    companion object {
        const val DEFAULT_STARTUP_CHANNEL_NUMBER = 1
        /**
         * Автозапуск при включении приставки человек включает сам. Раньше он был
         * включён сразу; приставки, обновлённые с тех версий, его сохраняют
         * ([StartOnBootMigration]).
         */
        const val DEFAULT_START_ON_BOOT = false
        const val DEFAULT_INVERT_ZAPPING = false
        const val DEFAULT_SHOW_STREAM_INFO = false
    }
}

/** Один файл настроек на процесс: DataStore нельзя открывать дважды. */
private val Context.tvSettingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "tv_settings",
    produceMigrations = { context -> listOf(StartOnBootMigration(context)) },
)

private val START_ON_BOOT_KEY = booleanPreferencesKey("start_on_boot")

/**
 * До версии 1.3.2 автозапуск был включён по умолчанию и в файл не записывался.
 * Один раз, пока значения в файле нет, записываем его явно: приложению,
 * обновлённому со старой версии, — «включено», как и работало; новой
 * установке — «выключено».
 */
private class StartOnBootMigration(private val context: Context) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) = START_ON_BOOT_KEY !in currentData

    override suspend fun migrate(currentData: Preferences): Preferences {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val enabled = startOnBootAfterUpdate(info.firstInstallTime, info.lastUpdateTime)
        return currentData.toMutablePreferences().apply { this[START_ON_BOOT_KEY] = enabled }
    }

    override suspend fun cleanUp() = Unit
}

/** Приложение стояло раньше (его обновили) — значит, автозапуск у него работал. */
internal fun startOnBootAfterUpdate(firstInstallTime: Long, lastUpdateTime: Long): Boolean =
    lastUpdateTime > firstInstallTime

/**
 * Настройки «простого плеера», которые меняет тот, кто ставит приставку.
 *
 * Можно получить через Hilt или напрямую [TvSettings.from] — например,
 * в приёмнике загрузки системы, где внедрения зависимостей нет.
 */
@Singleton
class TvSettings @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store: DataStore<Preferences> = context.applicationContext.tvSettingsStore

    val values: Flow<TvPreferences> = store.data
        // Повреждённый или нечитаемый файл не должен ронять приложение.
        .catch { error -> if (error is IOException) emit(EMPTY) else throw error }
        .map { it.toTvPreferences() }
        .distinctUntilChanged()

    val startupMode: Flow<StartupMode> = values.map { it.startupMode }.distinctUntilChanged()
    val startupChannelNumber: Flow<Int> = values.map { it.startupChannelNumber }.distinctUntilChanged()
    val startOnBoot: Flow<Boolean> = values.map { it.startOnBoot }.distinctUntilChanged()
    val invertZapping: Flow<Boolean> = values.map { it.invertZapping }.distinctUntilChanged()

    /** Текущие значения одним чтением. */
    suspend fun current(): TvPreferences = values.first()

    suspend fun setStartupMode(mode: StartupMode) {
        store.edit { it[Keys.STARTUP_MODE] = mode.code }
    }

    suspend fun setStartupChannelNumber(number: Int) {
        store.edit { it[Keys.STARTUP_CHANNEL_NUMBER] = number.coerceAtLeast(1) }
    }

    suspend fun setStartOnBoot(enabled: Boolean) {
        store.edit { it[Keys.START_ON_BOOT] = enabled }
    }

    suspend fun setInvertZapping(inverted: Boolean) {
        store.edit { it[Keys.INVERT_ZAPPING] = inverted }
    }

    suspend fun setShowStreamInfo(enabled: Boolean) {
        store.edit { it[Keys.SHOW_STREAM_INFO] = enabled }
    }

    /**
     * Переключает пропорции канала на следующие по кругу. Следующий вариант
     * считается внутри записи: два быстрых нажатия не дают один и тот же шаг.
     */
    suspend fun cycleChannelAspect(channelId: Long) {
        store.edit { prefs ->
            val aspects = decodeChannelAspects(prefs[Keys.CHANNEL_ASPECTS])
            val next = (aspects[channelId] ?: ChannelAspect.AUTO).next()
            prefs[Keys.CHANNEL_ASPECTS] = encodeChannelAspects(aspects + (channelId to next))
        }
    }

    private object Keys {
        val STARTUP_MODE = intPreferencesKey("startup_mode")
        val STARTUP_CHANNEL_NUMBER = intPreferencesKey("startup_channel_number")
        val START_ON_BOOT = START_ON_BOOT_KEY
        val INVERT_ZAPPING = booleanPreferencesKey("invert_zapping")
        val SHOW_STREAM_INFO = booleanPreferencesKey("show_stream_info")
        val CHANNEL_ASPECTS = stringPreferencesKey("channel_aspects")
    }

    private fun Preferences.toTvPreferences() = TvPreferences(
        startupMode = StartupMode.fromCode(this[Keys.STARTUP_MODE]),
        startupChannelNumber = (this[Keys.STARTUP_CHANNEL_NUMBER]
            ?: TvPreferences.DEFAULT_STARTUP_CHANNEL_NUMBER).coerceAtLeast(1),
        startOnBoot = this[Keys.START_ON_BOOT] ?: TvPreferences.DEFAULT_START_ON_BOOT,
        invertZapping = this[Keys.INVERT_ZAPPING] ?: TvPreferences.DEFAULT_INVERT_ZAPPING,
        showStreamInfo = this[Keys.SHOW_STREAM_INFO] ?: TvPreferences.DEFAULT_SHOW_STREAM_INFO,
        channelAspects = decodeChannelAspects(this[Keys.CHANNEL_ASPECTS]),
    )

    companion object {
        private val EMPTY: Preferences = emptyPreferences()

        fun from(context: Context): TvSettings = TvSettings(context)
    }
}
