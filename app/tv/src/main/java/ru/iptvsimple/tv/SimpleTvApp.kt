package ru.iptvsimple.tv

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ru.iptvsimple.engine.settings.StartupMode

/**
 * Корень приложения. Порядок экранов простой: заставка, потом эфир на весь
 * экран. Двойное «Назад» в эфире ведёт в настройки, «Назад» в настройках —
 * обратно к каналу. Если плейлистов нет, после заставки сразу настройки.
 */
@Composable
fun SimpleTvApp(model: TvViewModel = hiltViewModel()) {
    val screenState = model.state.collectAsStateWithLifecycle()
    val screen = screenState.value
    val page = remember { mutableStateOf(TvPage.Splash) }
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current

    // Вернуться к каналу удаётся, только если модели есть что включить.
    val backToTv: () -> Unit = {
        scope.launch { if (model.watchNow()) page.value = TvPage.Watching }
    }

    StartupRouting(screenState, page, onAutoPlayTaken = model::consumeAutoPlay)

    // Плейлисты сортируются по правилам языка системы.
    val systemLocale = LocalConfiguration.current.locales[0]
    LaunchedEffect(systemLocale) { model.applySystemLocale(systemLocale) }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.onAppStarted() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.onAppStopped() }

    // Без плейлиста «Назад» в настройках не перехватываем: приложение закроется.
    BackHandler(enabled = page.value == TvPage.Settings && screen.playlists.isNotEmpty(), onBack = backToTv)

    Box(
        Modifier
            .background(TvColors.Backdrop)
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        if (page.value == TvPage.Splash) {
            SplashLayer()
        } else if (page.value == TvPage.Settings) {
            TvSimpleSettingsScreen(
                state = screen,
                onWatchNow = backToTv,
                onStartupMode = model::setStartupMode,
                onStartupChannelNumber = model::setStartupChannelNumber,
                onStartOnBoot = model::setStartOnBoot,
                onInvertZapping = model::setInvertZapping,
                onAddPlaylist = model::addPlaylist,
                onRefreshPlaylists = model::refreshPlaylists,
                onQuit = {
                    // Плеер отпускаем сразу, не дожидаясь, пока система закроет окно.
                    model.stopWatching()
                    activity?.finish()
                },
            )
        }
        WatchingLayer(
            model = model,
            screen = screen,
            shown = page.value == TvPage.Watching,
            onLeave = {
                model.stopWatching()
                page.value = TvPage.Settings
            },
        )
    }
}

/**
 * Решает, куда уйти с заставки. Автозапуск модели сразу открывает эфир
 * (в том числе позже, когда плейлист добавили из настроек). Если включать
 * нечего — сразу настройки; если модель так и не решила — настройки через 10 с.
 */
@Composable
private fun StartupRouting(
    screenState: State<TvScreenState>,
    page: MutableState<TvPage>,
    onAutoPlayTaken: () -> Unit,
) {
    val autoPlay = screenState.value.autoPlayRequested
    LaunchedEffect(autoPlay) {
        if (!autoPlay) return@LaunchedEffect
        page.value = TvPage.Watching
        onAutoPlayTaken()
    }
    LaunchedEffect(Unit) {
        withTimeoutOrNull(SPLASH_LIMIT_MILLIS) {
            snapshotFlow { screenState.value.hasNothingToAutoPlay() }.first { it }
        }
        if (page.value == TvPage.Splash) page.value = TvPage.Settings
    }
}

private fun TvScreenState.hasNothingToAutoPlay(): Boolean =
    playlistsLoaded && (playlists.isEmpty() || startupMode == StartupMode.OFF)

/** Слой эфира: плавно появляется поверх остального и так же исчезает. */
@Composable
private fun WatchingLayer(
    model: TvViewModel,
    screen: TvScreenState,
    shown: Boolean,
    onLeave: () -> Unit,
) {
    AnimatedVisibility(visible = shown, enter = fadeIn(), exit = fadeOut()) {
        val activePlayer by model.player.collectAsStateWithLifecycle()
        val nowPlaying by model.currentChannel.collectAsStateWithLifecycle()
        val playing by model.isPlaying.collectAsStateWithLifecycle()
        val engineState by model.playbackState.collectAsStateWithLifecycle()
        val failed by model.playbackFailed.collectAsStateWithLifecycle()

        TvPlayerScreen(
            player = activePlayer,
            channel = nowPlaying,
            zapTarget = screen.zapTarget,
            queue = screen.queue,
            numbering = screen.numbered,
            favorites = screen.favorites,
            playlists = screen.playlists,
            panelPlaylistUrl = screen.panelPlaylistUrl,
            panelChannels = screen.panelChannels,
            isPlaying = playing,
            playbackState = engineState,
            playbackFailed = failed,
            recovering = screen.recovering,
            invertZapping = screen.invertZapping,
            onZap = model::zap,
            onPlayNumber = model::playNumber,
            onPlayFromPanel = { channel, group -> model.play(channel, group) },
            onToggleFavorite = model::toggleCurrentFavorite,
            onOpenPanel = model::openChannelPanel,
            onSelectPanelPlaylist = model::selectPanelPlaylist,
            onRestart = model::restartCurrent,
            onPlayPause = { model.setPaused(paused = playing) },
            onBack = onLeave,
        )
    }
}

/** Знак «SMPL|TV» посреди экрана, пока решается, можно ли сразу включить канал. */
@Composable
private fun SplashLayer() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        TvWordmark(fontSize = TvType.Display)
    }
}

/** Дольше заставку не держим, даже если модель ещё не решила. */
private const val SPLASH_LIMIT_MILLIS = 10_000L
