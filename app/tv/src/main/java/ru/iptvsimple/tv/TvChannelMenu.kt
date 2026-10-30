package ru.iptvsimple.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import ru.iptvsimple.engine.channel.Channel
import ru.iptvsimple.engine.settings.ChannelAspect
import kotlinx.coroutines.yield

/**
 * Действия с текущим каналом, открываются кнопкой «вправо». Избранное здесь
 * меняется только по ОК, чтобы случайное нажатие не правило список.
 */
@Composable
internal fun TvChannelMenu(
    channel: Channel,
    number: Int?,
    favorite: Boolean,
    aspect: ChannelAspect,
    onCycleAspect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRestart: () -> Unit,
    onOpenChannels: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstItem = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        yield()
        runCatching { firstItem.requestFocus() }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.Small),
        modifier = modifier
            .fillMaxHeight()
            .width(TvSizes.ChannelMenu)
            .clip(TvShapes.PanelEnd)
            .background(TvBrushes.Glass)
            .padding(horizontal = TvSpacing.Large, vertical = TvSpacing.XLarge)
    ) {
        Text(
            text = listOfNotNull(number?.toString(), channel.title.capitalizedWords()).joinToString("  "),
            color = TvColors.Ink,
            fontSize = TvType.Title,
            fontWeight = FontWeight.SemiBold,
            fontFamily = TvFonts.Ui,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (channel.group.isNotBlank()) {
            Text(
                text = channel.group,
                color = TvColors.InkSoft,
                fontSize = TvType.Body,
                fontFamily = TvFonts.Ui,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = TvSpacing.Medium),
            )
        }
        MenuItem(
            icon = if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            text = stringResource(
                if (favorite) R.string.tv_simple_menu_favorite_remove
                else R.string.tv_simple_menu_favorite_add
            ),
            focusRequester = firstItem,
            onClick = onToggleFavorite,
            onClose = onClose,
        )
        MenuItem(
            icon = Icons.Rounded.Refresh,
            text = stringResource(R.string.tv_simple_menu_restart),
            onClick = onRestart,
            onClose = onClose,
        )
        // Меню остаётся открытым: видно, как меняется картинка, и можно нажать ещё раз.
        MenuItem(
            icon = Icons.Rounded.AspectRatio,
            text = stringResource(R.string.tv_simple_menu_aspect, stringResource(aspect.labelRes())),
            onClick = onCycleAspect,
            onClose = onClose,
        )
        MenuItem(
            icon = Icons.AutoMirrored.Rounded.List,
            text = stringResource(R.string.tv_simple_menu_channels),
            onClick = onOpenChannels,
            onClose = onClose,
        )
    }
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    TvPressable(
        label = text,
        onPress = onClick,
        look = TvFocusLook.MenuRow,
        focusRequester = focusRequester,
        // «Влево» закрывает меню — так же, как «вправо» его открыло.
        onKeyDown = { key -> key == Key.DirectionLeft && run { onClose(); true } },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TvSizes.Row)
    ) { focused ->
        val color = if (focused) TvColors.OnFocusFill else TvColors.Ink
        Row(
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Medium),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = TvSpacing.Medium)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(TvSizes.Icon)
            )
            Text(
                text = text,
                color = color,
                fontSize = TvType.Body,
                fontWeight = FontWeight.Medium,
                fontFamily = TvFonts.Ui,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun ChannelAspect.labelRes(): Int = when (this) {
    ChannelAspect.AUTO -> R.string.tv_simple_aspect_auto
    ChannelAspect.CLASSIC -> R.string.tv_simple_aspect_classic
    ChannelAspect.FILM -> R.string.tv_simple_aspect_film
}
