package ru.iptvsimple.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VideoLibrary
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.yield
import ru.iptvsimple.engine.channel.Channel
import ru.iptvsimple.engine.settings.StartupMode

/** Настройки, у которых варианты открываются в боковой панели. */
private enum class SettingsPanel { Startup, Zapping, Playlists }

/**
 * Единственный экран настроек, устроен как системные настройки Google TV:
 * слева строки с текущим значением, справа панель с вариантами выбранной
 * строки. Он для того, кто настраивает приставку, а не для зрителя.
 */
@Composable
internal fun TvSimpleSettingsScreen(
    state: TvScreenState,
    onWatchNow: () -> Unit,
    onStartupMode: (StartupMode) -> Unit,
    onStartupChannelNumber: (Int) -> Unit,
    onStartOnBoot: (Boolean) -> Unit,
    onInvertZapping: (Boolean) -> Unit,
    onShowStreamInfo: (Boolean) -> Unit,
    onLoadStartupChannels: () -> Unit,
    onAddPlaylist: (String) -> Unit,
    onRefreshPlaylists: () -> Unit,
    onQuit: () -> Unit,
) {
    val context = LocalContext.current
    // Клавиатура от поля ссылки не должна оставаться поверх эфира,
    // когда после добавления плейлиста приложение само переходит к каналу.
    // Контроллер Compose закрывает её, только пока жив сеанс ввода, поэтому
    // дополнительно просим систему напрямую.
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        onDispose {
            keyboard?.hide()
            view.context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }
    var resumeCount by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeCount++ }
    val canStartOnBoot = remember(resumeCount) { AndroidSettings.canDrawOverlays(context) }
    var showPermissionHelp by remember { mutableStateOf(false) }
    val canWatch = state.playlists.isNotEmpty()

    var panel by remember { mutableStateOf<SettingsPanel?>(null) }
    var returnFocusTo by remember { mutableStateOf<SettingsPanel?>(null) }
    val watchRequester = remember { FocusRequester() }
    val rowRequesters = remember { SettingsPanel.entries.associateWith { FocusRequester() } }

    fun openPanel(target: SettingsPanel) {
        panel = target
    }

    fun closePanel() {
        returnFocusTo = panel
        panel = null
    }

    LaunchedEffect(Unit) {
        yield()
        runCatching {
            if (canWatch) watchRequester.requestFocus()
            else rowRequesters.getValue(SettingsPanel.Playlists).requestFocus()
        }
    }
    LaunchedEffect(panel, returnFocusTo) {
        val target = returnFocusTo
        if (panel == null && target != null) {
            yield()
            runCatching { rowRequesters.getValue(target).requestFocus() }
            returnFocusTo = null
        }
    }
    BackHandler(enabled = panel != null) { closePanel() }
    // Плейлисты обновились, пока панель открыта, — номера могли сдвинуться.
    LaunchedEffect(panel, state.playlists) {
        if (panel == SettingsPanel.Startup) onLoadStartupChannels()
    }

    val bootValue = when {
        !state.startOnBoot -> stringResource(R.string.tv_simple_off)
        !canStartOnBoot -> stringResource(R.string.tv_settings_boot_needs_permission)
        else -> stringResource(R.string.tv_simple_on)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(TvSpacing.Small),
            contentPadding = PaddingValues(
                start = TvSpacing.XXLarge,
                end = TvSpacing.Large,
                top = TvSpacing.XXLarge,
                bottom = TvSpacing.XXLarge,
            ),
            modifier = Modifier
                .width(TvSizes.SettingsList)
                .fillMaxHeight()
        ) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(TvSpacing.Small),
                    modifier = Modifier.padding(start = TvSpacing.Small, bottom = TvSpacing.Large),
                ) {
                    TvWordmark(fontSize = TvType.Label)
                    Text(
                        text = stringResource(R.string.tv_simple_settings_title),
                        color = TvColors.Ink,
                        fontSize = TvType.Headline,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = TvFonts.Ui,
                    )
                }
            }
            if (canWatch) {
                item {
                    SettingsRow(
                        icon = Icons.Rounded.PlayArrow,
                        title = stringResource(R.string.tv_simple_watch_now),
                        primary = true,
                        focusRequester = watchRequester,
                        onClick = onWatchNow,
                    )
                }
            }
            item { SectionLabel(stringResource(R.string.tv_settings_section_watch)) }
            item {
                SettingsRow(
                    icon = Icons.Rounded.Tv,
                    title = stringResource(R.string.tv_simple_startup_title),
                    value = startupValue(state),
                    opensPanel = true,
                    active = panel == SettingsPanel.Startup,
                    focusRequester = rowRequesters.getValue(SettingsPanel.Startup),
                    onClick = { openPanel(SettingsPanel.Startup) },
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Rounded.KeyboardArrowUp,
                    title = stringResource(R.string.tv_simple_zapping_title),
                    value = stringResource(
                        if (state.invertZapping) R.string.tv_simple_zapping_previous
                        else R.string.tv_simple_zapping_next
                    ),
                    opensPanel = true,
                    active = panel == SettingsPanel.Zapping,
                    focusRequester = rowRequesters.getValue(SettingsPanel.Zapping),
                    onClick = { openPanel(SettingsPanel.Zapping) },
                )
            }
            item {
                SettingsRow(
                    icon = Icons.Rounded.PowerSettingsNew,
                    title = stringResource(R.string.tv_simple_boot_title),
                    value = bootValue,
                    valueIsWarning = state.startOnBoot && !canStartOnBoot,
                    toggle = state.startOnBoot,
                    onClick = {
                        when {
                            !state.startOnBoot -> {
                                onStartOnBoot(true)
                                if (!canStartOnBoot) {
                                    showPermissionHelp = !requestOverlayPermission(context)
                                }
                            }

                            !canStartOnBoot ->
                                showPermissionHelp = !requestOverlayPermission(context)

                            else -> onStartOnBoot(false)
                        }
                    },
                )
            }
            if (state.startOnBoot && !canStartOnBoot && showPermissionHelp) {
                item {
                    HintText(
                        text = stringResource(R.string.tv_simple_boot_permission_manual, context.packageName),
                        modifier = Modifier.padding(horizontal = TvSpacing.Medium),
                    )
                }
            }
            item {
                SettingsRow(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.tv_settings_stream_info_title),
                    value = stringResource(if (state.showStreamInfo) R.string.tv_simple_on else R.string.tv_simple_off),
                    toggle = state.showStreamInfo,
                    onClick = { onShowStreamInfo(!state.showStreamInfo) },
                )
            }
            item { SectionLabel(stringResource(R.string.tv_settings_section_source)) }
            item {
                SettingsRow(
                    icon = Icons.Rounded.VideoLibrary,
                    title = stringResource(R.string.tv_settings_playlists_title),
                    value = if (canWatch) {
                        stringResource(R.string.tv_settings_playlists_value, state.channelCount)
                    } else {
                        stringResource(R.string.tv_settings_playlists_none)
                    },
                    opensPanel = true,
                    active = panel == SettingsPanel.Playlists,
                    focusRequester = rowRequesters.getValue(SettingsPanel.Playlists),
                    onClick = { openPanel(SettingsPanel.Playlists) },
                )
            }
            // «Назад» отсюда возвращает к каналу, поэтому выход — отдельной строкой внизу.
            item { Spacer(Modifier.height(TvSpacing.Large)) }
            item {
                SettingsRow(
                    icon = Icons.AutoMirrored.Rounded.ExitToApp,
                    title = stringResource(R.string.tv_settings_quit),
                    onClick = onQuit,
                )
            }
            item {
                HintText(
                    text = stringResource(
                        R.string.tv_settings_footer,
                        stringResource(R.string.app_name),
                        BuildConfig.VERSION_NAME,
                        state.favorites.size,
                    ),
                    modifier = Modifier.padding(start = TvSpacing.Medium, top = TvSpacing.Large),
                )
            }
        }

        when (panel) {
            SettingsPanel.Startup -> {
                val startupList = rememberLazyListState()
                val pickedRequester = remember { FocusRequester() }
                val byNumber = state.startupMode == StartupMode.CHANNEL_NUMBER
                val pickedIndex = state.startupChannels.orEmpty()
                    .indexOfFirst { it.number == state.startupChannelNumber }
                // Список длинный: сразу показываем и ставим фокус на выбранный канал.
                LaunchedEffect(byNumber, pickedIndex) {
                    if (!byNumber || pickedIndex < 0) return@LaunchedEffect
                    startupList.scrollToItem(STARTUP_CHANNELS_FIRST_ITEM + pickedIndex)
                    yield()
                    runCatching { pickedRequester.requestFocus() }
                }
                SidePanel(
                    title = stringResource(R.string.tv_simple_startup_title),
                    onClose = ::closePanel,
                    listState = startupList,
                    items = {
                        if (byNumber) {
                            startupChannelItems(
                                channels = state.startupChannels,
                                selectedNumber = state.startupChannelNumber,
                                pickedRequester = pickedRequester,
                                onPick = { number ->
                                    onStartupChannelNumber(number)
                                    closePanel()
                                },
                                onClose = ::closePanel,
                            )
                        }
                    },
                ) { firstRequester ->
                    StartupMode.entries.forEach { mode ->
                        OptionRow(
                            text = stringResource(mode.labelRes()),
                            selected = state.startupMode == mode,
                            focusRequester = firstRequester.takeIf { state.startupMode == mode },
                            onClose = ::closePanel,
                            onClick = {
                                onStartupMode(mode)
                                if (mode != StartupMode.CHANNEL_NUMBER) closePanel()
                            },
                        )
                    }
                }
            }

            SettingsPanel.Zapping -> SidePanel(
                title = stringResource(R.string.tv_simple_zapping_title),
                onClose = ::closePanel,
            ) { firstRequester ->
                listOf(false, true).forEach { inverted ->
                    OptionRow(
                        text = stringResource(
                            if (inverted) R.string.tv_simple_zapping_previous
                            else R.string.tv_simple_zapping_next
                        ),
                        selected = state.invertZapping == inverted,
                        focusRequester = firstRequester.takeIf { state.invertZapping == inverted },
                        onClose = ::closePanel,
                        onClick = {
                            onInvertZapping(inverted)
                            closePanel()
                        },
                    )
                }
            }

            SettingsPanel.Playlists -> SidePanel(
                title = stringResource(R.string.tv_settings_playlists_title),
                onClose = ::closePanel,
            ) { firstRequester ->
                PlaylistsPanelContent(
                    state = state,
                    firstRequester = firstRequester,
                    onAddPlaylist = onAddPlaylist,
                    onRefreshPlaylists = onRefreshPlaylists,
                    onClose = ::closePanel,
                )
            }

            null -> Unit
        }
    }
}

@Composable
private fun PlaylistsPanelContent(
    state: TvScreenState,
    firstRequester: FocusRequester,
    onAddPlaylist: (String) -> Unit,
    onRefreshPlaylists: () -> Unit,
    onClose: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val addRequester = remember { FocusRequester() }
    var playlistUrl by remember { mutableStateOf("") }
    var urlFocused by remember { mutableStateOf(false) }
    val hint = stringResource(R.string.tv_simple_playlist_hint)

    state.playlists.forEach { playlist ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TvSizes.RowShort)
                .padding(horizontal = TvSpacing.Medium)
        ) {
            Text(
                text = playlist.title,
                color = TvColors.Ink,
                fontSize = TvType.Body,
                fontFamily = TvFonts.Ui,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = playlist.channelCount.toString(),
                color = TvColors.InkSoft,
                fontSize = TvType.Body,
                fontFamily = TvFonts.Ui,
            )
        }
    }
    if (state.playlists.isNotEmpty()) {
        ActionRow(
            icon = Icons.Rounded.Refresh,
            text = stringResource(R.string.tv_simple_about_refresh),
            focusRequester = firstRequester,
            onClose = onClose,
            onClick = onRefreshPlaylists,
        )
        Spacer(Modifier.height(TvSpacing.Medium))
    }
    SectionLabel(stringResource(R.string.tv_simple_playlist_title))
    BasicTextField(
        value = playlistUrl,
        onValueChange = { playlistUrl = it },
        singleLine = true,
        textStyle = TextStyle(color = TvColors.Ink, fontSize = TvType.Body, fontFamily = TvFonts.Ui),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TvSizes.Row)
            .then(if (state.playlists.isEmpty()) Modifier.focusRequester(firstRequester) else Modifier)
            .onFocusChanged { urlFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                    Key.DirectionDown -> runCatching { addRequester.requestFocus() }.isSuccess
                    else -> false
                }
            }
            .semantics { contentDescription = hint }
            .clip(TvShapes.Medium)
            .background(if (urlFocused) TvColors.CardRaised else TvColors.FieldIdle)
            .padding(horizontal = TvSpacing.Medium, vertical = TvSizes.FieldPaddingVertical),
        decorationBox = { innerTextField ->
            if (playlistUrl.isEmpty()) {
                Text(hint, color = TvColors.InkFaint, fontSize = TvType.Body, fontFamily = TvFonts.Ui)
            }
            innerTextField()
        },
    )
    // Во время загрузки кнопка не выключается, а просто не реагирует:
    // выключенная теряет фокус, он уходит в поле ссылки, и система снова
    // открывает клавиатуру — она оставалась висеть поверх эфира.
    ActionRow(
        icon = Icons.Rounded.Add,
        text = stringResource(R.string.tv_simple_playlist_add),
        focusRequester = addRequester,
        onClose = onClose,
        onClick = { if (!state.playlistImportInProgress) onAddPlaylist(playlistUrl) },
    )
    val feedback = when {
        state.playlistImportInProgress -> R.string.tv_simple_playlist_adding
        else -> when (state.playlistImportFeedback) {
            TvPlaylistImportFeedback.Added -> R.string.tv_simple_playlist_added
            TvPlaylistImportFeedback.Refreshed -> R.string.tv_settings_playlists_refreshed
            TvPlaylistImportFeedback.Failed -> R.string.tv_simple_playlist_failed
            TvPlaylistImportFeedback.InvalidUrl -> R.string.tv_simple_playlist_invalid
            null -> null
        }
    }
    feedback?.let {
        HintText(stringResource(it), modifier = Modifier.padding(horizontal = TvSpacing.Medium))
    }
}

/**
 * Варианты одной настройки; панель выезжает поверх правой части экрана.
 * Длинный список (например, все каналы) идёт после [content] в [items]:
 * так строятся только видимые строки.
 */
@Composable
private fun SidePanel(
    title: String,
    onClose: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
    items: LazyListScope.() -> Unit = {},
    content: @Composable (firstRequester: FocusRequester) -> Unit,
) {
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        yield()
        runCatching { firstRequester.requestFocus() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(TvSpacing.Small),
            contentPadding = PaddingValues(
                horizontal = TvSpacing.Large,
                vertical = TvSpacing.XXLarge,
            ),
            modifier = Modifier
                .width(TvSizes.SidePanel)
                .fillMaxHeight()
                .clip(TvShapes.PanelEnd)
                .background(TvBrushes.Glass)
                .tvSteadyRepeat()
        ) {
            item {
                Text(
                    text = title,
                    color = TvColors.Ink,
                    fontSize = TvType.Title,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = TvFonts.Ui,
                    modifier = Modifier.padding(
                        start = TvSpacing.Medium,
                        bottom = TvSpacing.Medium,
                    ),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(TvSpacing.Small)) {
                    content(firstRequester)
                }
            }
            items()
        }
    }
}

/** Первые строки панели — заголовок, варианты и подпись, за ними идут каналы. */
private const val STARTUP_CHANNELS_FIRST_ITEM = 3

/**
 * Каналы, из которых выбирают стартовый: номер — место канала в плейлисте.
 * [channels] = null — список ещё грузится.
 */
private fun LazyListScope.startupChannelItems(
    channels: List<Channel>?,
    selectedNumber: Int,
    pickedRequester: FocusRequester,
    onPick: (Int) -> Unit,
    onClose: () -> Unit,
) {
    item { SectionLabel(stringResource(R.string.tv_settings_startup_channel)) }
    if (channels.isNullOrEmpty()) {
        item {
            HintText(
                text = stringResource(
                    if (channels == null) R.string.tv_settings_startup_channels_loading
                    else R.string.tv_simple_panel_empty
                ),
                modifier = Modifier.padding(horizontal = TvSpacing.Medium),
            )
        }
        return
    }
    items(channels, key = { it.id }) { channel ->
        val picked = channel.number == selectedNumber
        OptionRow(
            text = "${channel.number}. ${channel.title.capitalizedWords()}",
            selected = picked,
            focusRequester = pickedRequester.takeIf { picked },
            onClick = { onPick(channel.number) },
            onClose = onClose,
        )
    }
}

/** Строка основного списка: значок, название, значение и стрелка или переключатель. */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    value: String? = null,
    valueIsWarning: Boolean = false,
    primary: Boolean = false,
    opensPanel: Boolean = false,
    active: Boolean = false,
    toggle: Boolean? = null,
    focusRequester: FocusRequester? = null,
) {
    TvPressable(
        label = listOfNotNull(title, value).joinToString(". "),
        onPress = onClick,
        look = TvFocusLook.ListRow,
        tinted = primary || active,
        focusRequester = focusRequester,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TvSizes.RowTall)
    ) { focused ->
        val content = when {
            focused -> TvColors.OnFocusFill
            primary || active -> TvColors.OnChosen
            else -> TvColors.Ink
        }
        val secondary = when {
            focused -> TvColors.OnFocusFill.copy(alpha = 0.7f)
            valueIsWarning -> TvColors.Warning
            primary || active -> TvColors.OnChosen.copy(alpha = 0.8f)
            else -> TvColors.InkSoft
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = TvSpacing.Comfortable)
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(TvSizes.Icon))
            Text(
                text = title,
                color = content,
                fontSize = TvType.Body,
                fontWeight = FontWeight.SemiBold,
                fontFamily = TvFonts.Ui,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (value != null) {
                Text(
                    text = value,
                    color = secondary,
                    fontSize = TvType.Label,
                    fontFamily = TvFonts.Ui,
                    maxLines = 1,
                )
            }
            when {
                toggle != null -> ToggleIndicator(checked = toggle, focused = focused)
                opensPanel -> Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = secondary,
                    modifier = Modifier.size(TvSizes.Icon),
                )
            }
        }
    }
}

/** Один вариант в боковой панели; закрашенная отметка только у выбранного. */
@Composable
private fun OptionRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    PanelRow(
        icon = if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
        text = text,
        iconTint = if (selected) TvColors.Brand else TvColors.InkFaint,
        onClick = onClick,
        onClose = onClose,
        focusRequester = focusRequester,
    )
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    PanelRow(
        icon = icon,
        text = text,
        iconTint = TvColors.Ink,
        onClick = onClick,
        onClose = onClose,
        focusRequester = focusRequester,
    )
}

@Composable
private fun PanelRow(
    icon: ImageVector,
    text: String,
    iconTint: Color,
    onClick: () -> Unit,
    onClose: () -> Unit,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    TvPressable(
        label = text,
        onPress = onClick,
        look = TvFocusLook.MenuRow,
        available = enabled,
        focusRequester = focusRequester,
        // «Влево» закрывает панель — так же, как строка слева её открыла.
        onKeyDown = { key -> key == Key.DirectionLeft && run { onClose(); true } },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TvSizes.Row)
    ) { focused ->
        val color = when {
            !enabled -> TvColors.InkFaint
            focused -> TvColors.OnFocusFill
            else -> TvColors.Ink
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = TvSpacing.Medium)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (focused) TvColors.OnFocusFill else iconTint,
                modifier = Modifier.size(TvSizes.Icon),
            )
            Text(
                text = text,
                color = color,
                fontSize = TvType.Body,
                fontWeight = FontWeight.Medium,
                fontFamily = TvFonts.Ui,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Плоский переключатель вкл/выкл, нарисованный только токенами дизайна. */
@Composable
private fun ToggleIndicator(checked: Boolean, focused: Boolean) {
    val track = when {
        checked && focused -> TvColors.OnFocusFill
        checked -> TvColors.Brand
        focused -> TvColors.OnFocusFill.copy(alpha = 0.3f)
        else -> TvColors.CardRaised
    }
    val thumb = when {
        checked && focused -> TvColors.FocusFill
        checked -> TvColors.OnFocusFill
        else -> TvColors.InkFaint
    }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .width(TvSizes.ToggleTrackWidth)
            .height(TvSizes.ToggleTrackHeight)
            .clip(CircleShape)
            .background(track)
    ) {
        Box(
            Modifier
                .offset(x = if (checked) TvSizes.ToggleKnobOn else TvSizes.ToggleKnobOff)
                .size(TvSizes.ToggleKnob)
                .clip(CircleShape)
                .background(thumb)
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TvColors.InkSoft,
        fontSize = TvType.Label,
        fontWeight = FontWeight.SemiBold,
        fontFamily = TvFonts.Ui,
        modifier = Modifier.padding(start = TvSpacing.Medium, top = TvSpacing.Large, bottom = TvSpacing.XSmall),
    )
}

@Composable
private fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = TvColors.InkFaint,
        fontSize = TvType.Label,
        fontFamily = TvFonts.Ui,
        modifier = modifier,
    )
}

@Composable
private fun startupValue(state: TvScreenState): String =
    if (state.startupMode == StartupMode.CHANNEL_NUMBER) {
        stringResource(R.string.tv_simple_startup_number_value, state.startupChannelNumber)
    } else {
        stringResource(state.startupMode.labelRes())
    }

/** Открывает системный экран «Поверх других окон»; false, если на приставке его нет. */
private fun requestOverlayPermission(context: Context): Boolean {
    val intent = Intent(
        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private fun StartupMode.labelRes(): Int = when (this) {
    StartupMode.OFF -> R.string.tv_simple_startup_off
    StartupMode.LAST_WATCHED -> R.string.tv_simple_startup_last
    StartupMode.FIRST_FAVORITE -> R.string.tv_simple_startup_favorite
    StartupMode.CHANNEL_NUMBER -> R.string.tv_simple_startup_number
}

