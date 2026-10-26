package ru.iptvsimple.tv

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.iptvsimple.engine.channel.Channel
import ru.iptvsimple.engine.channel.ChannelRepository
import ru.iptvsimple.engine.db.PlaylistWithCount
import ru.iptvsimple.engine.net.NetworkMonitor
import ru.iptvsimple.engine.player.TvPlayerEngine
import ru.iptvsimple.engine.playlist.ImportResult
import ru.iptvsimple.engine.playlist.PlaylistRepository
import ru.iptvsimple.engine.settings.StartupMode
import ru.iptvsimple.engine.settings.TvPreferences
import ru.iptvsimple.engine.settings.TvSettings
import java.util.Locale
import javax.inject.Inject

/** Всё, что показывают экраны приложения. */
@Immutable
data class TvScreenState(
    /** Плейлисты с числом каналов, по алфавиту. */
    val playlists: List<PlaylistWithCount> = emptyList(),
    /** База ответила хотя бы раз (пусть даже плейлистов нет). */
    val playlistsLoaded: Boolean = false,
    val favorites: List<Channel> = emptyList(),
    val recent: Channel? = null,
    /** Список, по которому листают кнопками вверх/вниз. */
    val queue: List<Channel> = emptyList(),
    /** Весь плейлист текущего канала: по нему работают номера и цифры на пульте. */
    val numbered: TvChannelNumbering = TvChannelNumbering.EMPTY,
    /** Канал, на который зритель листает, пока кнопку не отпустили. */
    val zapTarget: Channel? = null,
    val autoPlayRequested: Boolean = false,
    val panelPlaylistUrl: String? = null,
    val panelChannels: List<Channel> = emptyList(),
    val startupMode: StartupMode = StartupMode.DEFAULT,
    val startupChannelNumber: Int = TvPreferences.DEFAULT_STARTUP_CHANNEL_NUMBER,
    val startOnBoot: Boolean = TvPreferences.DEFAULT_START_ON_BOOT,
    val invertZapping: Boolean = TvPreferences.DEFAULT_INVERT_ZAPPING,
    val playlistImportInProgress: Boolean = false,
    val playlistImportFeedback: TvPlaylistImportFeedback? = null,
    /** Сторож перезапускает зависший канал. */
    val recovering: Boolean = false,
    /** У приставки нет сети: канал включится сам, как только она появится. */
    val networkOffline: Boolean = false,
    /** Язык системы: по его правилам плейлисты идут по алфавиту. */
    val playlistLocale: Locale = Locale.getDefault(),
) {
    val channelCount: Int get() = playlists.sumOf { it.channelCount }
}

sealed interface TvPlaylistImportFeedback {
    data object InvalidUrl : TvPlaylistImportFeedback
    data object Failed : TvPlaylistImportFeedback
    data object Added : TvPlaylistImportFeedback
    data object Refreshed : TvPlaylistImportFeedback
}

/**
 * Логика «простого плеера»: автозапуск, переключение каналов, группы,
 * избранное, сторож зависаний и плавная громкость. Сам плеер живёт в
 * движке ([TvPlayerEngine]); отметку «просмотрен» ставит тоже движок.
 */
@HiltViewModel
class TvViewModel @Inject constructor(
    private val engine: TvPlayerEngine,
    private val settings: TvSettings,
    private val playlistRepository: PlaylistRepository,
    private val channelRepository: ChannelRepository,
    private val network: NetworkMonitor,
) : ViewModel() {
    /** Единственный изменяемый источник состояния экранов. */
    private val screen = MutableStateFlow(TvScreenState())
    val state: StateFlow<TvScreenState> = screen.asStateFlow()

    val player: StateFlow<Player?> = engine.player
    val currentChannel: StateFlow<Channel?> = engine.channel
    val isPlaying: StateFlow<Boolean> = engine.isPlaying
    val playbackState: StateFlow<Int> = engine.playbackState
    val playbackFailed: StateFlow<Boolean> = engine.error
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var zapJob: Job? = null
    private var panelJob: Job? = null
    /** Загрузка нумерации и очереди для только что включённого канала. */
    private var numberingJob: Job? = null
    private var importJob: Job? = null

    /** Зритель смотрит канал (плеер не остановлен кнопкой «Назад» или сворачиванием). */
    @Volatile
    private var watching = false

    /** Канал, который надо включить снова, когда приложение вернётся на экран. */
    private var channelToResume: Channel? = null

    private val stallDetector = TvStallDetector()
    private var stallRestarts = 0
    private var watchdogTicks = 0

    /**
     * Счётчик запусков канала. Каждый запуск (и перезапуск сторожем) его
     * увеличивает: от этого заново начинаются слежка сторожа и подъём громкости.
     */
    private val launches = MutableStateFlow(0)

    /** Когда каждый плейлист обновлялся в последний раз (по данным базы). */
    private val knownRevisions = HashMap<String, Long>()

    init {
        // Данные из базы и настроек сразу переносим в состояние экранов.
        playlistRepository.observePlaylists().feedInto { playlists ->
            copy(playlists = playlists.sortedByTitle(playlistLocale), playlistsLoaded = true)
        }
        followPlaylistUpdates()
        channelRepository.observeFavorites().feedInto { copy(favorites = it) }
        channelRepository.observeLastWatched().feedInto { copy(recent = it) }
        settings.values.feedInto { values ->
            copy(
                startupMode = values.startupMode,
                startupChannelNumber = values.startupChannelNumber,
                startOnBoot = values.startOnBoot,
                invertZapping = values.invertZapping,
            )
        }
        network.state.feedInto { copy(networkOffline = !it.online) }
        retryAfterErrors()
        rampVolumeOnStart()
        watchForStalls()
        restartWhenNetworkReturns()
        addBuiltInPlaylist()
        startOnLaunch()
    }

    /** Язык системы сменился — плейлисты пересортировываются по его алфавиту. */
    fun applySystemLocale(locale: Locale) {
        screen.update { current ->
            if (current.playlistLocale == locale) current
            else current.copy(playlistLocale = locale, playlists = current.playlists.sortedByTitle(locale))
        }
    }

    /** Обновляет все плейлисты и показывает итог в панели «Плейлисты». */
    fun refreshPlaylists() {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            screen.update { it.copy(playlistImportInProgress = true, playlistImportFeedback = null) }
            try {
                val results = playlistRepository.refreshAll()
                val feedback = if (results.all { it is ImportResult.Success }) {
                    TvPlaylistImportFeedback.Refreshed
                } else {
                    TvPlaylistImportFeedback.Failed
                }
                screen.update { it.copy(playlistImportFeedback = feedback) }
            } finally {
                screen.update { it.copy(playlistImportInProgress = false) }
            }
        }
    }

    /**
     * Включает [channel], а [queue] делает списком для кнопок вверх/вниз.
     * Без [queue] листаем весь плейлист канала.
     */
    fun play(channel: Channel, queue: List<Channel>? = null) {
        zapJob?.cancel()
        // Прежняя загрузка могла быть для другого плейлиста — её результат уже не нужен.
        numberingJob?.cancel()
        screen.update { it.copy(zapTarget = null, queue = queue ?: it.queue) }
        numberingJob = viewModelScope.launch {
            val numbering = numberingFor(channel.playlistUrl)
            if (queue == null) {
                screen.update { it.copy(queue = numbering.channels.ifEmpty { listOf(channel) }) }
            }
        }
        startChannel(channel)
    }

    /**
     * Листание вверх/вниз. Пока кнопку жмут часто, на экране меняется только
     * название; канал включается, когда пульт отпустили.
     */
    fun zap(steps: Int) {
        val current = state.value
        val queue = current.queue
        if (queue.isEmpty()) return
        val from = current.zapTarget ?: currentChannel.value
        val index = from?.let { channel -> queue.indexOfFirst { it.id == channel.id } } ?: -1
        val target = if (index < 0) queue.first() else queue[tvZapIndex(index, queue.size, steps)]
        // Звук старого канала гасим сразу, с первого нажатия.
        if (current.zapTarget == null) silence()
        screen.update { it.copy(zapTarget = target) }
        zapJob?.cancel()
        zapJob = viewModelScope.launch {
            delay(ZAP_SETTLE_MILLIS)
            startChannel(target)
            screen.update { it.copy(zapTarget = null) }
        }
    }

    /**
     * Канал по номеру, набранному цифрами. Номер постоянный — место в плейлисте.
     * Если канал не входит в текущую группу, дальше листаем весь плейлист.
     */
    fun playNumber(number: Int) {
        val numbering = state.value.numbered
        val target = numbering.channelAt(number) ?: return
        zapJob?.cancel()
        numberingJob?.cancel()
        screen.update { current ->
            val inQueue = current.queue.any { it.id == target.id }
            current.copy(zapTarget = null, queue = if (inQueue) current.queue else numbering.channels)
        }
        startChannel(target)
    }

    fun restartCurrent() {
        val channel = state.value.zapTarget ?: currentChannel.value ?: return
        zapJob?.cancel()
        screen.update { it.copy(zapTarget = null) }
        startChannel(channel)
    }

    fun consumeAutoPlay() {
        screen.update { it.copy(autoPlayRequested = false) }
    }

    /** Новое состояние избранного у показанного канала или null, если канала нет. */
    fun toggleCurrentFavorite(): Boolean? {
        val channel = state.value.zapTarget ?: currentChannel.value ?: return null
        val nowFavorite = state.value.favorites.none { it.id == channel.id }
        toggleFavorite(channel)
        return nowFavorite
    }

    fun openChannelPanel() {
        val url = (state.value.zapTarget ?: currentChannel.value)?.playlistUrl
            ?: state.value.playlists.firstOrNull()?.url
            ?: return
        selectPanelPlaylist(url)
    }

    fun selectPanelPlaylist(url: String) {
        panelJob?.cancel()
        panelJob = viewModelScope.launch {
            val channels = channelRepository.byPlaylist(url)
            screen.update { it.copy(panelPlaylistUrl = url, panelChannels = channels) }
        }
    }

    /** Приложение ушло с экрана: плеер освобождаем, канал запоминаем. */
    fun onAppStopped() {
        if (!watching) return
        channelToResume = state.value.zapTarget ?: currentChannel.value
        zapJob?.cancel()
        screen.update { it.copy(zapTarget = null) }
        stopWatching()
    }

    fun onAppStarted() {
        val channel = channelToResume ?: return
        channelToResume = null
        startChannel(channel)
    }

    fun setStartupMode(mode: StartupMode) {
        viewModelScope.launch { settings.setStartupMode(mode) }
    }

    fun setStartupChannelNumber(number: Int) {
        viewModelScope.launch { settings.setStartupChannelNumber(number) }
    }

    fun setStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { settings.setStartOnBoot(enabled) }
    }

    fun setInvertZapping(enabled: Boolean) {
        viewModelScope.launch { settings.setInvertZapping(enabled) }
    }

    fun addPlaylist(url: String) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            screen.update { it.copy(playlistImportInProgress = true, playlistImportFeedback = null) }
            try {
                val feedback = when (val result = playlistRepository.add(url)) {
                    is ImportResult.Success -> TvPlaylistImportFeedback.Added
                    is ImportResult.Failure ->
                        if (result.reason == ImportResult.Reason.INVALID_URL) {
                            TvPlaylistImportFeedback.InvalidUrl
                        } else {
                            TvPlaylistImportFeedback.Failed
                        }
                }
                screen.update { it.copy(playlistImportFeedback = feedback) }
            } finally {
                screen.update { it.copy(playlistImportInProgress = false) }
            }
        }
    }

    /**
     * «Смотреть ТВ» из настроек: канал, который шёл, иначе последний
     * просмотренный, иначе первый канал первого плейлиста.
     * false — смотреть пока нечего.
     */
    suspend fun watchNow(): Boolean {
        val channel = currentChannel.value
            ?: state.value.zapTarget
            ?: state.value.recent
            ?: state.value.playlists.firstOrNull()
                ?.let { channelRepository.byPlaylist(it.url).firstOrNull() }
            ?: return false
        play(channel)
        return true
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch { channelRepository.toggleFavorite(channel.id) }
    }

    /** Пауза по кнопке пульта; плеер при этом не пересоздаётся. */
    fun setPaused(paused: Boolean) {
        engine.setPaused(paused)
    }

    /** Зритель ушёл из эфира: звук гасим сразу, плеер освобождаем. */
    fun stopWatching() {
        watching = false
        silence()
        engine.release()
    }

    /** Номера каналов берём из всего плейлиста, а не из группы. */
    private suspend fun numberingFor(playlistUrl: String): TvChannelNumbering {
        val current = state.value.numbered
        if (current.playlistUrl == playlistUrl) return current
        val numbering = TvChannelNumbering(playlistUrl, channelRepository.byPlaylist(playlistUrl))
        currentCoroutineContext().ensureActive()
        screen.update { it.copy(numbered = numbering) }
        return numbering
    }

    /**
     * Плейлист обновляется сам раз в сутки, пока приложение открыто. Каналы
     * сохраняют свои id, но номера, названия и ссылки могли поменяться, а
     * новые каналы — появиться. Поэтому нумерацию, очередь листания, панель
     * и играющий канал перечитываем из базы, когда меняется время обновления.
     */
    private fun followPlaylistUpdates() {
        viewModelScope.launch {
            playlistRepository.observePlaylists().collect { playlists ->
                for (playlist in playlists) {
                    val before = knownRevisions.put(playlist.url, playlist.updatedAt)
                    if (before != null && before != playlist.updatedAt) reloadPlaylist(playlist.url)
                }
            }
        }
    }

    private suspend fun reloadPlaylist(playlistUrl: String) {
        val fresh = channelRepository.byPlaylist(playlistUrl)
        if (fresh.isEmpty()) return
        val byId = fresh.associateBy { it.id }
        // Канал этого плейлиста заменяем свежей копией; пропавший из файла оставляем как был.
        fun Channel.renewed(): Channel = if (this.playlistUrl == playlistUrl) byId[id] ?: this else this
        screen.update { current ->
            val numberedHere = current.numbered.playlistUrl == playlistUrl
            // Листали весь плейлист — листаем весь обновлённый, иначе только те же каналы.
            val wholePlaylist = numberedHere && current.queue.isNotEmpty() &&
                current.queue.size == current.numbered.channels.size &&
                current.queue.all { it.playlistUrl == playlistUrl }
            current.copy(
                numbered = if (numberedHere) TvChannelNumbering(playlistUrl, fresh) else current.numbered,
                queue = if (wholePlaylist) fresh else current.queue.map { it.renewed() },
                zapTarget = current.zapTarget?.renewed(),
                panelChannels = if (current.panelPlaylistUrl == playlistUrl) fresh else current.panelChannels,
            )
        }
        channelToResume = channelToResume?.renewed()
        currentChannel.value?.renewed()?.let(engine::refreshChannelInfo)
        Log.i(WATCHDOG_TAG, "плейлист обновлён, каналов: ${fresh.size}")
    }

    /** Запуск канала по желанию зрителя: счётчик попыток сторожа обнуляется. */
    private fun startChannel(channel: Channel) {
        stallRestarts = 0
        screen.update { it.copy(recovering = false) }
        watching = true
        silence()
        launches.update { it + 1 }
        viewModelScope.launch { engine.play(channel) }
    }

    /** Перезапуск зависшего канала сторожем; отметку «просмотрен» не ставит. */
    private fun restartStalled() {
        silence()
        launches.update { it + 1 }
        engine.restart()
    }

    /**
     * Первая помощь каналу, который застрял на загрузке: тот же плеер, то же
     * место, обрываются только зависшие соединения. Скачанное остаётся.
     * Новым запуском это не считается: звук не глушим, сторож не сбрасываем.
     */
    private fun resumeStalled() {
        stallDetector.restartBufferingTimer(SystemClock.elapsedRealtime())
        engine.resume()
    }

    /** Глушит текущий канал сразу, чтобы звук не оборвался на полуслове. */
    private fun silence() {
        engine.player.value?.volume = 0f
    }

    /**
     * Каждый канал начинается беззвучно, а когда картинка пошла, звук плавно
     * поднимается за 0,3 с. Так при переключении нет щелчков и писка.
     * Движок создаёт новый плеер на каждый запуск, поэтому следим за ним.
     */
    private fun rampVolumeOnStart() {
        viewModelScope.launch {
            combine(engine.player, launches) { player, _ -> player }
                .collectLatest { player ->
                    if (player == null) return@collectLatest
                    player.volume = 0f
                    // Ждём именно нового запуска, а не готовности прежнего.
                    withTimeoutOrNull(RAMP_WAIT_RESTART_MILLIS) {
                        engine.playbackState.first { it != Player.STATE_READY }
                    }
                    engine.isPlaying.first { it }
                    for (step in 1..RAMP_STEPS) {
                        delay(RAMP_MILLIS / RAMP_STEPS)
                        player.volume = step.toFloat() / RAMP_STEPS
                    }
                }
        }
    }

    /**
     * Сторож зависаний. Раз в 2 с смотрит на плеер и перезапускает канал,
     * если тот не стартовал, завис на загрузке, застыл или «закончился».
     * После нескольких неудач ждёт дольше, чтобы не дёргать сервер.
     * Журнал: adb logcat -s TvWatchdog.
     */
    private fun watchForStalls() {
        viewModelScope.launch {
            launches.collectLatest {
                stallDetector.reset(SystemClock.elapsedRealtime())
                while (true) {
                    delay(STALL_CHECK_MILLIS)
                    // Без сети перезапуски бесполезны: канал включится сам, когда сеть появится.
                    if (!watching || state.value.zapTarget != null || !network.state.value.online) {
                        stallDetector.reset(SystemClock.elapsedRealtime())
                        continue
                    }
                    val player = engine.player.value
                    val playbackState = engine.playbackState.value
                    if (player != null && ++watchdogTicks % HEALTH_LOG_EVERY == 0) {
                        Log.d(
                            WATCHDOG_TAG,
                            "health: state=$playbackState " +
                                "buffered=${player.totalBufferedDuration / 1000}s " +
                                "liveOffset=${player.currentLiveOffset.takeIf { it >= 0 }?.div(1000)}s",
                        )
                    }
                    val reason = stallDetector.check(
                        now = SystemClock.elapsedRealtime(),
                        state = playbackState,
                        playWhenReady = player?.playWhenReady ?: true,
                        position = player?.currentPosition ?: 0L,
                    )
                    if (reason == null) {
                        if (playbackState == Player.STATE_READY && state.value.recovering) {
                            Log.i(WATCHDOG_TAG, "канал ожил после перезапусков: $stallRestarts")
                            stallRestarts = 0
                            screen.update { it.copy(recovering = false) }
                        }
                        continue
                    }
                    val channel = currentChannel.value ?: continue
                    stallRestarts++
                    // Застрял на загрузке посреди просмотра — сначала пробуем
                    // продолжить с того же места. Не помогло или картинка
                    // застыла — полный перезапуск.
                    val soft = stallRestarts == 1 && reason == TvStallReason.STUCK_BUFFERING
                    Log.w(
                        WATCHDOG_TAG,
                        "${if (soft) "продолжение" else "перезапуск"} №$stallRestarts " +
                            "канала ${channel.number} (${channel.title}): $reason",
                    )
                    screen.update { it.copy(recovering = true) }
                    if (stallRestarts > STALL_QUICK_RESTARTS) delay(STALL_SLOW_RESTART_MILLIS)
                    if (soft) resumeStalled() else restartStalled()
                }
            }
        }
    }

    /**
     * Живой эфир иногда обрывается. Пока зритель смотрит, через 5 с пробуем
     * снова — чтобы картинка вернулась без нажатий.
     */
    private fun retryAfterErrors() {
        viewModelScope.launch {
            engine.error.collectLatest { error ->
                if (error == null) return@collectLatest
                delay(ERROR_RETRY_MILLIS)
                // Без сети не пробуем: канал перезапустится, как только она появится.
                if (watching && engine.error.value != null && network.state.value.online) engine.restart()
            }
        }
    }

    /**
     * После включения приставки сеть появляется не сразу, а потом ещё может
     * смениться (поднимается VPN). Канал, запущенный раньше, к этому времени
     * висит на ошибках «адрес не найден». Как только сеть появилась или
     * сменилась, а канал не играет, перезапускаем его сразу, не дожидаясь сторожа.
     */
    private fun restartWhenNetworkReturns() {
        viewModelScope.launch {
            var previous = network.state.value
            network.state.collectLatest { now ->
                val returned = now.online && (!previous.online || now.networkId != previous.networkId)
                previous = now
                if (!returned || !watching) return@collectLatest
                // Даём сети секунду: адреса серверов начинают находиться не мгновенно.
                delay(NETWORK_SETTLE_MILLIS)
                val playing = engine.playbackState.value == Player.STATE_READY && engine.error.value == null
                if (!watching || playing || currentChannel.value == null) return@collectLatest
                Log.i(WATCHDOG_TAG, "сеть появилась (${now.networkId}) — перезапускаю канал сразу")
                stallRestarts = 0
                screen.update { it.copy(recovering = false) }
                restartStalled()
            }
        }
    }

    /** Каждое новое значение потока [reduce] вписывает в состояние экранов. */
    private fun <T> Flow<T>.feedInto(reduce: TvScreenState.(T) -> TvScreenState) {
        viewModelScope.launch {
            collect { value -> screen.update { it.reduce(value) } }
        }
    }

    /** Плейлист, вшитый в локальную сборку (-PtvDefaultPlaylistUrl), если других нет. */
    private fun addBuiltInPlaylist() {
        val url = BuildConfig.TV_DEFAULT_PLAYLIST_URL.trim()
        if (url.isEmpty()) return
        viewModelScope.launch {
            if (playlistRepository.playlists().isEmpty()) addPlaylist(url)
        }
    }

    /**
     * Как только есть плейлист с каналами, сразу включает канал на весь
     * экран — чтобы после включения приставки сразу шла картинка.
     */
    private fun startOnLaunch() {
        viewModelScope.launch {
            val preferences = settings.current()
            if (preferences.startupMode == StartupMode.OFF) return@launch
            val playlists = playlistRepository.observePlaylists()
                .map { all -> all.filter { it.channelCount > 0 }.sortedByTitle(state.value.playlistLocale) }
                .first { it.isNotEmpty() }
            val recent = channelRepository.lastWatched()
            val favorites = channelRepository.observeFavorites().first()
            val firstPlaylistUrl = playlists.first().url
            val loaded = listOfNotNull(recent?.playlistUrl, firstPlaylistUrl)
                .distinct()
                .associateWith { url -> channelRepository.byPlaylist(url) }
            val selection = tvStartupSelection(
                mode = preferences.startupMode,
                channelNumber = preferences.startupChannelNumber,
                recent = recent,
                favorites = favorites,
                playlistChannels = { url -> loaded[url].orEmpty() },
                firstPlaylistUrl = firstPlaylistUrl,
            ) ?: return@launch
            // Зритель успел включить канал сам — не перебиваем.
            if (watching) return@launch
            val playlistUrl = selection.channel.playlistUrl
            val numbering = TvChannelNumbering(
                playlistUrl = playlistUrl,
                channels = loaded[playlistUrl] ?: channelRepository.byPlaylist(playlistUrl),
            )
            screen.update {
                it.copy(queue = selection.queue, numbered = numbering, autoPlayRequested = true)
            }
            startChannel(selection.channel)
        }
    }

    private companion object {
        const val ZAP_SETTLE_MILLIS = 450L
        const val ERROR_RETRY_MILLIS = 5_000L
        const val NETWORK_SETTLE_MILLIS = 1_000L
        const val RAMP_MILLIS = 300L
        const val RAMP_STEPS = 10
        const val RAMP_WAIT_RESTART_MILLIS = 3_000L
        const val STALL_CHECK_MILLIS = 2_000L
        const val HEALTH_LOG_EVERY = 5
        const val STALL_QUICK_RESTARTS = 3
        const val STALL_SLOW_RESTART_MILLIS = 20_000L
        const val WATCHDOG_TAG = "TvWatchdog"
    }
}

private fun List<PlaylistWithCount>.sortedByTitle(locale: Locale): List<PlaylistWithCount> =
    sortedWith(byVisibleText(locale, PlaylistWithCount::title))
