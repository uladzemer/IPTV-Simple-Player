package ru.iptvsimple.tv

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import ru.iptvsimple.engine.channel.Channel
import ru.iptvsimple.engine.db.PlaylistWithCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield

/**
 * Эфир на весь экран. Пульт работает без видимого фокуса: вверх/вниз
 * переключают каналы, влево открывает группы, вправо — меню канала,
 * ОК показывает плашку канала, цифры включают канал по номеру.
 */
// PlayerSurface в media3 пока помечен как нестабильный API.
@OptIn(UnstableApi::class)
@Composable
fun TvPlayerScreen(
    player: Player?,
    channel: Channel?,
    zapTarget: Channel?,
    queue: List<Channel>,
    numbering: TvChannelNumbering,
    favorites: List<Channel>,
    playlists: List<PlaylistWithCount>,
    panelPlaylistUrl: String?,
    panelChannels: List<Channel>,
    isPlaying: Boolean,
    playbackState: Int,
    playbackFailed: Boolean,
    recovering: Boolean,
    invertZapping: Boolean,
    onZap: (Int) -> Unit,
    onPlayNumber: (Int) -> Unit,
    onPlayFromPanel: (Channel, List<Channel>) -> Unit,
    onToggleFavorite: () -> Boolean?,
    onOpenPanel: () -> Unit,
    onSelectPanelPlaylist: (String) -> Unit,
    onRestart: () -> Unit,
    onPlayPause: () -> Unit,
    onBack: () -> Unit,
) {
    val view = LocalView.current
    val keyFocusRequester = remember { FocusRequester() }
    val displayed = zapTarget ?: channel
    val displayedNumber = displayed?.let(numbering::numberOf)
    val favoriteIds = remember(favorites) { favorites.mapTo(mutableSetOf()) { it.id } }

    var panelVisible by remember { mutableStateOf(false) }
    var menuVisible by remember { mutableStateOf(false) }
    var infoToken by remember { mutableIntStateOf(0) }
    var infoVisible by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageToken by remember { mutableIntStateOf(0) }
    var backArmed by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }

    val favoriteAdded = stringResource(R.string.tv_simple_favorite_added)
    val favoriteRemoved = stringResource(R.string.tv_simple_favorite_removed)
    val backAgain = stringResource(R.string.tv_simple_back_again)

    fun showMessage(text: String) {
        message = text
        messageToken++
    }

    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(displayed?.id) {
        if (displayed != null) infoToken++
    }
    LaunchedEffect(infoToken) {
        infoVisible = true
        delay(INFO_VISIBLE_MILLIS)
        infoVisible = false
    }
    LaunchedEffect(messageToken) {
        if (message == null) return@LaunchedEffect
        delay(MESSAGE_VISIBLE_MILLIS)
        message = null
        backArmed = false
    }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(DIGIT_COMMIT_MILLIS)
        digits.toIntOrNull()?.let(onPlayNumber)
        digits = ""
    }
    LaunchedEffect(panelVisible, menuVisible) {
        if (!panelVisible && !menuVisible) {
            yield()
            runCatching { keyFocusRequester.requestFocus() }
        }
    }

    BackHandler {
        when {
            menuVisible -> menuVisible = false
            panelVisible -> panelVisible = false
            backArmed -> {
                backArmed = false
                onBack()
            }

            else -> {
                backArmed = true
                showMessage(backAgain)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (player != null) {
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(keyFocusRequester)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val digit = event.nativeKeyEvent.keyCode.toDigit()
                    when {
                        digit != null -> {
                            digits = (digits + digit).takeLast(MAX_DIGITS)
                            true
                        }

                        event.key == Key.DirectionUp -> {
                            onZap(if (invertZapping) -1 else 1)
                            true
                        }

                        event.key == Key.DirectionDown -> {
                            onZap(if (invertZapping) 1 else -1)
                            true
                        }

                        event.key == Key.ChannelUp -> {
                            onZap(1)
                            true
                        }

                        event.key == Key.ChannelDown -> {
                            onZap(-1)
                            true
                        }

                        event.key == Key.DirectionLeft || event.key == Key.Menu -> {
                            onOpenPanel()
                            panelVisible = true
                            true
                        }

                        event.key == Key.DirectionRight -> {
                            if (displayed != null) menuVisible = true
                            true
                        }

                        event.key == Key.DirectionCenter ||
                            event.key == Key.Enter ||
                            event.key == Key.NumPadEnter -> {
                            if (digits.isNotEmpty()) {
                                digits.toIntOrNull()?.let(onPlayNumber)
                                digits = ""
                            } else {
                                infoToken++
                            }
                            true
                        }

                        event.key == Key.MediaPlayPause ||
                            event.key == Key.MediaPlay ||
                            event.key == Key.MediaPause -> {
                            onPlayPause()
                            true
                        }

                        else -> false
                    }
                }
                .focusable()
        )

        val status = when {
            playbackFailed -> stringResource(R.string.tv_simple_no_signal)
            recovering -> stringResource(R.string.tv_simple_recovering)
            zapTarget != null || playbackState == Player.STATE_BUFFERING ->
                stringResource(R.string.tv_simple_loading)

            playbackState == Player.STATE_READY && !isPlaying ->
                stringResource(R.string.tv_simple_paused)

            else -> null
        }

        AnimatedVisibility(
            visible = (infoVisible || playbackFailed || recovering) && displayed != null &&
                !panelVisible && !menuVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            if (displayed != null) {
                ChannelBanner(
                    channel = displayed,
                    number = displayedNumber,
                    favorite = displayed.id in favoriteIds,
                    status = status,
                )
            }
        }

        if (status != null && !infoVisible && !playbackFailed && !recovering && !panelVisible) {
            OverlayCapsule(
                text = status,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(TvSpacing.XLarge)
            )
        }

        message?.let { text ->
            OverlayCapsule(
                text = text,
                fontSize = TvType.Title,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = TvSpacing.Wide)
            )
        }

        if (digits.isNotEmpty()) {
            OverlayCapsule(
                text = digits,
                fontSize = TvType.Digits,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(TvSpacing.Wide)
            )
        }

        if (panelVisible) {
            TvChannelPanel(
                playlists = playlists,
                panelPlaylistUrl = panelPlaylistUrl,
                channels = panelChannels,
                favorites = favorites,
                queue = queue,
                numbering = numbering,
                currentChannelId = displayed?.id,
                onPlay = { target, group ->
                    panelVisible = false
                    onPlayFromPanel(target, group)
                },
                onSelectPlaylist = onSelectPanelPlaylist,
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }

        if (menuVisible && displayed != null) {
            TvChannelMenu(
                channel = displayed,
                number = displayedNumber,
                favorite = displayed.id in favoriteIds,
                onToggleFavorite = {
                    menuVisible = false
                    onToggleFavorite()?.let { added ->
                        showMessage(if (added) favoriteAdded else favoriteRemoved)
                    }
                },
                onRestart = {
                    menuVisible = false
                    onRestart()
                },
                onOpenChannels = {
                    menuVisible = false
                    onOpenPanel()
                    panelVisible = true
                },
                onClose = { menuVisible = false },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
}

@Composable
private fun ChannelBanner(
    channel: Channel,
    number: Int?,
    favorite: Boolean,
    status: String?,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .background(TvColors.Veil)
            .padding(horizontal = TvSpacing.XXLarge, vertical = TvSpacing.Large)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (number != null) {
                Text(
                    text = number.toString(),
                    color = TvColors.OnChosen,
                    fontSize = TvType.Display,
                    fontWeight = FontWeight.Bold,
                    fontFamily = TvFonts.Ui,
                    modifier = Modifier
                        .clip(TvShapes.Small)
                        .background(TvColors.Chosen)
                        .padding(horizontal = TvSpacing.Comfortable, vertical = TvSpacing.XSmall)
                )
            }
            if (!channel.logo.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(TvSizes.LogoLarge)
                )
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(TvSpacing.XSmall),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TvSpacing.Compact),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = channel.title.capitalizedWords(),
                        color = TvColors.Ink,
                        fontSize = TvType.Display,
                        fontWeight = FontWeight.Bold,
                        fontFamily = TvFonts.Ui,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (favorite) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = TvColors.Star,
                            modifier = Modifier.size(TvSizes.IconLarge)
                        )
                    }
                }
                val subtitle = listOfNotNull(
                    channel.group.takeIf { it.isNotBlank() },
                    status,
                ).joinToString(separator = "  ·  ")
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        color = TvColors.InkSoft,
                        fontSize = TvType.Title,
                        fontFamily = TvFonts.Ui,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.XLarge)) {
            listOf(
                stringResource(R.string.tv_simple_hint_zap),
                stringResource(R.string.tv_simple_hint_groups),
                stringResource(R.string.tv_simple_hint_favorite),
            ).forEach { hint ->
                Text(
                    text = hint,
                    color = TvColors.InkSoft,
                    fontSize = TvType.Body,
                    fontFamily = TvFonts.Ui,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun OverlayCapsule(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TvType.Body,
) {
    Text(
        text = text,
        color = TvColors.Ink,
        fontSize = fontSize,
        fontWeight = FontWeight.SemiBold,
        fontFamily = TvFonts.Ui,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = TvSizes.MessageMax)
            .clip(TvShapes.Medium)
            .background(TvColors.Veil)
            .padding(horizontal = TvSpacing.Large, vertical = TvSpacing.Compact)
    )
}

private fun Int.toDigit(): Int? = when (this) {
    in AndroidKeyEvent.KEYCODE_0..AndroidKeyEvent.KEYCODE_9 -> this - AndroidKeyEvent.KEYCODE_0
    in AndroidKeyEvent.KEYCODE_NUMPAD_0..AndroidKeyEvent.KEYCODE_NUMPAD_9 ->
        this - AndroidKeyEvent.KEYCODE_NUMPAD_0

    else -> null
}

private const val INFO_VISIBLE_MILLIS = 5_000L
private const val MESSAGE_VISIBLE_MILLIS = 3_000L
private const val DIGIT_COMMIT_MILLIS = 2_000L
private const val MAX_DIGITS = 4
