package ru.iptvsimple.engine.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import ru.iptvsimple.engine.channel.Channel
import ru.iptvsimple.engine.channel.ChannelRepository
import ru.iptvsimple.engine.net.DefaultUserAgent
import ru.iptvsimple.engine.net.PlaybackConnections
import java.io.IOException
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единственный плеер приложения.
 *
 * Все настройки ниже проверены на приставке (X96 X10 Pro, Amlogic S905X5), менять их
 * без проверки на железе нельзя. С плеером работаем только в главном потоке:
 * [play] сам переходит в него, остальные команды переносятся туда при вызове
 * из другого потока.
 *
 * Отметку «канал просмотрен» ставит только этот класс, один раз на запуск
 * канала через [play]; перезапуск сторожем ([restart]) её не ставит.
 */
@OptIn(UnstableApi::class)
@Singleton
class TvPlayerEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    @DefaultUserAgent private val defaultUserAgent: String,
    private val channels: ChannelRepository,
) {
    /** Здесь обрываются соединения: закрывать сокеты в главном потоке нельзя. */
    private val networkCleaner = Executors.newSingleThreadExecutor()

    private val _player = MutableStateFlow<Player?>(null)
    private val _channel = MutableStateFlow<Channel?>(null)
    private val _playbackState = MutableStateFlow(Player.STATE_IDLE)
    private val _isPlaying = MutableStateFlow(false)
    private val _error = MutableStateFlow<PlaybackException?>(null)

    /** Текущий плеер; меняется при каждом запуске канала. */
    val player: StateFlow<Player?> = _player.asStateFlow()

    /** Канал, который сейчас играет (или запускается). */
    val channel: StateFlow<Channel?> = _channel.asStateFlow()

    /** Состояние плеера: Player.STATE_IDLE, BUFFERING, READY, ENDED. */
    val playbackState: StateFlow<Int> = _playbackState.asStateFlow()

    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Ошибка, после которой плеер сам уже не восстановится. */
    val error: StateFlow<PlaybackException?> = _error.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Номер поколения. Каждый запуск канала получает новый номер, и события
     * от плееров прошлых поколений просто отбрасываются: так быстрое
     * переключение каналов не путает состояние.
     */
    @Volatile
    private var generation = 0L

    /** Что сейчас запущено: поколение, запрос и номер попытки формата. */
    private var session: Session? = null

    private class Session(
        val generation: Long,
        val player: ExoPlayer,
        val request: StreamRequest,
        /** Свои соединения на каждый запуск: их можно оборвать разом. */
        val connections: PlaybackConnections,
        var formatIndex: Int,
    )

    private val renderers by lazy { buildRenderers() }

    /** Запускает канал с начала. Старый плеер освобождается до создания нового. */
    suspend fun play(channel: Channel) {
        val started = withContext(Dispatchers.Main.immediate) { start(channel) }
        markWatchedOnce(channel, started)
    }

    /** Перезапускает текущий канал (так делает сторож зависаний). */
    fun restart() = onMain {
        val current = _channel.value ?: return@onMain
        Log.i(TAG, "перезапуск канала ${current.number}")
        start(current)
    }

    /**
     * Мягкое восстановление без нового плеера: обрывает зависшие соединения,
     * и плеер сам перезапрашивает кусок — недокачанный с того байта, где
     * остановился. Буфер и позиция остаются, картинка продолжается с той же
     * секунды.
     */
    fun resume() = onMain {
        val current = session ?: return@onMain
        Log.i(TAG, "мягкое восстановление канала ${_channel.value?.number} с ${current.player.currentPosition / 1000} с")
        cutConnections(current.connections) {
            onMain {
                // Пока обрывали, зритель мог переключить канал.
                if (session !== current) return@onMain
                if (current.player.playbackState == Player.STATE_IDLE) {
                    _error.value = null
                    current.player.prepare()
                }
                current.player.playWhenReady = true
            }
        }
    }

    /** Останавливает воспроизведение и освобождает плеер. */
    fun release() = onMain {
        generation++
        dropPlayer()
        _channel.value = null
    }

    /**
     * Подменяет сведения об играющем канале свежими после обновления плейлиста
     * (номер, название, ссылка), не прерывая показ. Новая ссылка понадобится
     * при следующем перезапуске. Чужой канал не трогаем.
     */
    fun refreshChannelInfo(fresh: Channel) = onMain {
        if (_channel.value?.id == fresh.id) _channel.value = fresh
    }

    /** Пауза или продолжение без пересоздания плеера. */
    fun setPaused(paused: Boolean) = onMain {
        val player = session?.player ?: return@onMain
        if (paused) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.playWhenReady = true
        }
    }

    // Возвращает номер поколения этого запуска.
    private fun start(channel: Channel): Long {
        val mine = ++generation
        dropPlayer()
        _channel.value = channel

        val request = StreamPlan.request(channel.streamUrl, defaultUserAgent)
        if (request.dropped.isNotEmpty()) {
            Log.w(TAG, "канал ${channel.number}: пропускаю заголовки не из ASCII: ${request.dropped}")
        }
        val player = buildPlayer(mine)
        val current = Session(mine, player, request, PlaybackConnections(httpClient), formatIndex = 0)
        session = current
        _player.value = player
        openFormat(current)
        return mine
    }

    private suspend fun markWatchedOnce(channel: Channel, startedGeneration: Long) {
        // Пока шла запись, зритель мог уже переключиться дальше — тогда не отмечаем.
        if (startedGeneration != generation) return
        try {
            channels.markWatched(channel.id)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            Log.w(TAG, "не удалось отметить просмотр канала ${channel.number}", failure)
        }
    }

    private fun openFormat(current: Session) {
        val format = current.request.formats[current.formatIndex]
        // Ссылку целиком не пишем: в ней часто ключ доступа зрителя.
        Log.i(TAG, "канал ${_channel.value?.number}: открываю как $format")
        _error.value = null
        current.player.setMediaSource(mediaSource(current.connections, current.request, format))
        current.player.prepare()
    }

    private fun dropPlayer() {
        val old = session ?: return
        session = null
        _player.value = null
        _playbackState.value = Player.STATE_IDLE
        _isPlaying.value = false
        _error.value = null
        old.player.stop()
        old.player.release()
        cutConnections(old.connections)
    }

    /**
     * Обрывает все соединения видео. Зависшее чтение иначе висит до таймаута,
     * а провайдер считает его занятым и может не пустить новое соединение.
     */
    private fun cutConnections(connections: PlaybackConnections, then: () -> Unit = {}) {
        networkCleaner.execute {
            connections.cutAll()
            then()
        }
    }

    private fun handleError(mine: Long, error: PlaybackException) {
        val current = session?.takeIf { it.generation == mine } ?: return
        val code = error.errorCode
        when {
            code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                // Отстали от живого окна — прыгаем обратно к прямому эфиру.
                Log.w(TAG, "отстали от прямого эфира, догоняю")
                current.player.seekToDefaultPosition()
                current.player.prepare()
            }

            StreamPlan.isFormatMismatch(code) &&
                current.formatIndex + 1 < current.request.formats.size -> {
                Log.w(TAG, "${error.errorCodeName}: пробую другой формат")
                current.formatIndex++
                openFormat(current)
            }

            else -> {
                Log.w(TAG, "ошибка воспроизведения ${error.errorCodeName}", error)
                _error.value = error
            }
        }
    }

    private fun buildPlayer(mine: Long): ExoPlayer {
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setForceHighestSupportedBitrate(true)
                    // Туннель выключен всегда: с ним HDMI-звук перезапускается
                    // при каждом переключении и телевизор пищит.
                    .setTunnelingEnabled(false),
            )
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(BUFFER_MIN_MS, BUFFER_MAX_MS, BUFFER_START_MS, BUFFER_RESUME_MS)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audio, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(Watcher(mine))
                // Дорожки, декодеры, потерянные кадры — всё видно в logcat -s TvPlayer.
                addAnalyticsListener(EventLogger(TAG))
                addAnalyticsListener(LoadLog)
                playWhenReady = true
            }
    }

    /** Передаёт события плеера дальше, только если он ещё актуален. */
    private inner class Watcher(private val mine: Long) : Player.Listener {
        private val actual get() = session?.generation == mine

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (actual) _playbackState.value = playbackState
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (actual) _isPlaying.value = isPlaying
        }

        override fun onPlayerError(error: PlaybackException) {
            handleError(mine, error)
        }
    }

    /**
     * Журнал загрузок кусочков: сколько байт и за сколько миллисекунд.
     * Видно, какой сервер «задумывается». Пишется только имя файла, без ключей.
     */
    private object LoadLog : AnalyticsListener {
        override fun onLoadCompleted(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
        ) {
            Log.d(
                NET_TAG,
                "${kind(mediaLoadData)} ${loadEventInfo.uri.lastPathSegment} " +
                    "${loadEventInfo.bytesLoaded / 1024} КБ за ${loadEventInfo.loadDurationMs} мс",
            )
        }

        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            Log.w(
                NET_TAG,
                "${kind(mediaLoadData)} ${loadEventInfo.uri.lastPathSegment} сбой через " +
                    "${loadEventInfo.loadDurationMs} мс (${loadEventInfo.bytesLoaded / 1024} КБ): " +
                    "${error.javaClass.simpleName} ${error.message.orEmpty().take(80)}",
            )
        }

        private fun kind(data: MediaLoadData) =
            if (data.dataType == C.DATA_TYPE_MEDIA) "кусок" else "список"
    }

    private fun mediaSource(
        connections: PlaybackConnections,
        request: StreamRequest,
        format: StreamFormat,
    ): MediaSource {
        val network: DataSource.Factory = OkHttpDataSource.Factory(connections.callFactory)
            .setUserAgent(request.userAgent)
            .setDefaultRequestProperties(request.headers)
        val item = MediaItem.Builder()
            .setUri(request.url)
            .setLiveConfiguration(
                // Держимся на 40 с позади эфира: запас видео прячет «задумчивость»
                // сервера, а постоянная скорость не даёт звуку плыть.
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_OFFSET_MS)
                    .setMinOffsetMs(LIVE_MIN_OFFSET_MS)
                    .setMinPlaybackSpeed(1f)
                    .setMaxPlaybackSpeed(1f)
                    .build(),
            )
            .build()
        return when (format) {
            StreamFormat.HLS -> HlsMediaSource.Factory(network)
                .setAllowChunklessPreparation(false)
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(LOAD_RETRIES))
                .setPlaylistParserFactory(LiveHoldback.ParserFactory())
                .setExtractorFactory(DefaultHlsExtractorFactory(TS_FLAGS, true))
                .createMediaSource(item)

            StreamFormat.PROGRESSIVE -> ProgressiveMediaSource.Factory(
                network,
                DefaultExtractorsFactory().setTsExtractorFlags(TS_FLAGS),
            ).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(LOAD_RETRIES))
                .createMediaSource(item)
        }
    }

    /**
     * Видео — только аппаратные декодеры (процессор приставки не тянет
     * программное 4K), звук — с FFmpeg, чтобы играл MP2.
     *
     * Звук всегда декодируется в обычный PCM, без передачи AC3/E-AC3 «как есть»
     * на HDMI. С такой передачей на Amlogic часы плеера идут неверно, и видео
     * американских каналов (MPEG-2 + AC3) шло замедленно; к тому же громкость
     * приложения на неё не действует, а HDMI переключается между форматами
     * при каждой смене канала.
     */
    private fun buildRenderers(): DefaultRenderersFactory =
        object : NextRenderersFactory(context) {
            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>,
            ) {
                super.buildVideoRenderers(
                    context,
                    EXTENSION_RENDERER_MODE_OFF,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out,
                )
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder()
                // Без context: иначе выход сам спрашивает HDMI о форматах
                // и снова включает передачу AC3 «как есть».
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build()
        }.apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private companion object {
        const val TAG = "TvPlayer"
        const val NET_TAG = "TvNet"

        /** Повторов на каждый кусок: с короткими таймаутами лучше больше попыток. */
        const val LOAD_RETRIES = 8

        // Без IDR-кадров вещательный IPTV иначе ждёт первую картинку вечно.
        // Именно «or»: с «and» часть каналов показывает чёрный экран.
        const val TS_FLAGS = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS

        const val LIVE_OFFSET_MS = 40_000L
        const val LIVE_MIN_OFFSET_MS = 20_000L

        const val BUFFER_MIN_MS = 30_000
        const val BUFFER_MAX_MS = 60_000
        const val BUFFER_START_MS = 2_500
        const val BUFFER_RESUME_MS = 6_000
    }
}
