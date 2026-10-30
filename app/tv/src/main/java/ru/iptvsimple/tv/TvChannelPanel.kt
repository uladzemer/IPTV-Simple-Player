package ru.iptvsimple.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import ru.iptvsimple.engine.channel.Channel
import ru.iptvsimple.engine.db.PlaylistWithCount
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Панель из двух колонок: слева группы, справа каналы группы под фокусом.
 * При переходе по группам их каналы показываются сразу.
 */
@Composable
internal fun TvChannelPanel(
    playlists: List<PlaylistWithCount>,
    panelPlaylistUrl: String?,
    channels: List<Channel>,
    favorites: List<Channel>,
    queue: List<Channel>,
    numbering: TvChannelNumbering,
    currentChannelId: Long?,
    onPlay: (Channel, List<Channel>) -> Unit,
    onSelectPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val favoritesTitle = stringResource(R.string.tv_simple_group_favorites)
    val allTitle = stringResource(R.string.tv_simple_group_all)
    val uncategorizedTitle = stringResource(R.string.tv_simple_group_uncategorized)
    val groups = remember(channels, favorites, favoritesTitle, allTitle, uncategorizedTitle) {
        tvChannelGroups(channels, favorites, favoritesTitle, allTitle, uncategorizedTitle)
    }
    val favoriteIds = remember(favorites) { favorites.mapTo(mutableSetOf()) { it.id } }
    val initialGroupKey = remember(groups, queue, currentChannelId) {
        val queueIds = queue.map { it.id }
        groups.firstOrNull { group -> group.channels.map { it.id } == queueIds }?.key
            ?: groups.firstOrNull { group ->
                group.kind == TvChannelGroupKind.CATEGORY &&
                    group.channels.any { it.id == currentChannelId }
            }?.key
            ?: groups.firstOrNull()?.key
    }
    var selectedGroupKey by remember(initialGroupKey) { mutableStateOf(initialGroupKey) }
    val selectedGroup = groups.firstOrNull { it.key == selectedGroupKey } ?: groups.firstOrNull()
    val groupRequesters = remember(groups) { groups.associate { it.key to FocusRequester() } }
    val playlistRequesters = remember(playlists) {
        playlists.associate { it.url to FocusRequester() }
    }
    val channelRequester = remember { FocusRequester() }
    val groupListState = rememberLazyListState()
    val channelListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showPlaylists = playlists.size > 1

    // Каналы группы всегда листаются с начала, даже если идущий канал
    // в середине списка: он и так виден по синей подложке.
    fun focusChannels() {
        if (selectedGroup?.channels.isNullOrEmpty()) return
        scope.launch {
            channelListState.scrollToItem(0)
            yield()
            runCatching { channelRequester.requestFocus() }
        }
    }

    fun focusSelectedGroup() {
        val group = selectedGroup ?: return
        scope.launch {
            groupListState.scrollToItem(groups.indexOf(group).coerceAtLeast(0))
            yield()
            runCatching { groupRequesters[group.key]?.requestFocus() }
        }
    }

    // Другая группа под фокусом — её каналы показываем с начала.
    LaunchedEffect(selectedGroup?.key) { channelListState.scrollToItem(0) }

    LaunchedEffect(groups.isEmpty(), panelPlaylistUrl) {
        if (groups.isNotEmpty()) {
            focusSelectedGroup()
        } else if (showPlaylists) {
            yield()
            runCatching { playlistRequesters[panelPlaylistUrl]?.requestFocus() }
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(TvSpacing.Medium),
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(0.72f)
            .clip(TvShapes.PanelStart)
            .background(TvBrushes.Glass)
            .padding(TvSpacing.XLarge)
    ) {
        LazyColumn(
            state = groupListState,
            verticalArrangement = Arrangement.spacedBy(TvSpacing.Small),
            contentPadding = PaddingValues(vertical = TvSpacing.Small, horizontal = TvSpacing.Comfortable),
            modifier = Modifier
                .width(TvSizes.GroupColumn)
                .fillMaxHeight()
                .tvSteadyRepeat()
        ) {
            item { PanelHeader(stringResource(R.string.tv_simple_panel_groups)) }
            itemsIndexed(groups, key = { _, group -> group.key }) { _, group ->
                PanelRow(
                    text = group.title,
                    trailing = group.channels.size.toString(),
                    selected = group.key == selectedGroup?.key,
                    focusRequester = groupRequesters[group.key],
                    onFocus = { selectedGroupKey = group.key },
                    onClick = { focusChannels() },
                    onKeyDown = { key ->
                        if (key == Key.DirectionRight) {
                            focusChannels()
                            true
                        } else {
                            false
                        }
                    },
                )
            }
            if (showPlaylists) {
                item { PanelHeader(stringResource(R.string.tv_simple_panel_playlists)) }
                itemsIndexed(playlists, key = { _, playlist -> "playlist:${playlist.url}" }) { _, playlist ->
                    PanelRow(
                        text = playlist.title,
                        selected = playlist.url == panelPlaylistUrl,
                        focusRequester = playlistRequesters[playlist.url],
                        onClick = { onSelectPlaylist(playlist.url) },
                    )
                }
            }
        }

        val groupChannels = selectedGroup?.channels.orEmpty()
        LazyColumn(
            state = channelListState,
            verticalArrangement = Arrangement.spacedBy(TvSpacing.Small),
            contentPadding = PaddingValues(vertical = TvSpacing.Small, horizontal = TvSpacing.Comfortable),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .tvSteadyRepeat()
        ) {
            item { PanelHeader(selectedGroup?.title.orEmpty()) }
            if (groupChannels.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.tv_simple_panel_empty),
                        color = TvColors.InkSoft,
                        fontSize = TvType.BodyLarge,
                        fontFamily = TvFonts.Ui,
                    )
                }
            }
            itemsIndexed(groupChannels, key = { _, channel -> channel.id }) { index, channel ->
                ChannelRow(
                    number = numbering.numberOf(channel) ?: (index + 1),
                    channel = channel,
                    playing = channel.id == currentChannelId,
                    favorite = channel.id in favoriteIds,
                    focusRequester = channelRequester.takeIf { index == 0 },
                    onClick = { onPlay(channel, groupChannels) },
                    onKeyDown = { key ->
                        if (key == Key.DirectionLeft) {
                            focusSelectedGroup()
                            true
                        } else {
                            false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PanelHeader(text: String) {
    Text(
        text = text,
        color = TvColors.InkSoft,
        fontSize = TvType.Caption,
        fontWeight = FontWeight.SemiBold,
        fontFamily = TvFonts.Ui,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = TvSpacing.Small, top = TvSpacing.Small, bottom = TvSpacing.XSmall)
    )
}

@Composable
private fun PanelRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    trailing: String? = null,
    onFocus: () -> Unit = {},
    onKeyDown: (Key) -> Boolean = { false },
) {
    TvPressable(
        label = text,
        onPress = onClick,
        look = TvFocusLook.ListRow,
        tinted = selected,
        inChoiceList = true,
        focusRequester = focusRequester,
        onFocused = onFocus,
        onKeyDown = onKeyDown,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TvSizes.RowCompact)
    ) { focused ->
        val color = when {
            focused -> TvColors.OnFocusFill
            selected -> TvColors.OnChosen
            else -> TvColors.Ink
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Compact),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = TvSpacing.Medium, vertical = TvSpacing.Small)
        ) {
            Text(
                text = text,
                color = color,
                fontSize = TvType.BodyLarge,
                fontWeight = FontWeight.SemiBold,
                fontFamily = TvFonts.Ui,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (trailing != null) {
                Text(
                    text = trailing,
                    color = color.copy(alpha = 0.7f),
                    fontSize = TvType.Caption,
                    fontFamily = TvFonts.Ui,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ChannelRow(
    number: Int,
    channel: Channel,
    playing: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
    onKeyDown: (Key) -> Boolean,
) {
    val title = channel.title.capitalizedWords()
    TvPressable(
        label = "$number. $title",
        onPress = onClick,
        look = TvFocusLook.ListRow,
        tinted = playing,
        inChoiceList = true,
        focusRequester = focusRequester,
        onKeyDown = onKeyDown,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TvSizes.Row)
    ) { focused ->
        val color = when {
            focused -> TvColors.OnFocusFill
            playing -> TvColors.OnChosen
            else -> TvColors.Ink
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = TvSpacing.Medium, vertical = TvSpacing.Small)
        ) {
            Text(
                text = number.toString(),
                color = color.copy(alpha = 0.8f),
                fontSize = TvType.Body,
                fontWeight = FontWeight.SemiBold,
                fontFamily = TvFonts.Ui,
                maxLines = 1,
                modifier = Modifier.width(TvSizes.NumberColumn)
            )
            if (!channel.logo.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(TvSizes.LogoSmall)
                )
            }
            Text(
                text = title,
                color = color,
                fontSize = TvType.BodyLarge,
                fontWeight = FontWeight.SemiBold,
                fontFamily = TvFonts.Ui,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (favorite) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = if (focused) TvColors.OnFocusFill else TvColors.Star,
                    modifier = Modifier.size(TvSizes.Icon)
                )
            }
        }
    }
}
