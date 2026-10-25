package ru.iptvsimple.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.zIndex

/**
 * Как элемент выглядит, когда на нём фокус пульта: во сколько раз он
 * подрастает и какой формы его подложка.
 */
@Immutable
class TvFocusLook(val grow: Float, val shape: Shape) {
    companion object {
        /** Отдельная плитка: заметно подрастает. */
        val Tile = TvFocusLook(grow = 1.04f, shape = TvShapes.Small)

        /** Строка длинного списка: растёт чуть-чуть, чтобы не задевать соседей. */
        val ListRow = TvFocusLook(grow = 1.02f, shape = TvShapes.Medium)

        /** Строка меню или боковой панели: не растёт, только светлеет. */
        val MenuRow = TvFocusLook(grow = 1f, shape = TvShapes.Medium)
    }
}

/** Кнопки пульта, которые означают «нажать». */
private val PressKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * Нажимаемый пультом элемент.
 *
 * В фокусе подложка светлая (текст на ней тёмный) и элемент подрастает по [look];
 * при [tinted] без фокуса подложка тихо-синяя. «ОК» срабатывает при отпускании
 * кнопки. [onKeyDown] первым получает нажатия других кнопок и может забрать их себе.
 *
 * Недоступный элемент ([available] = false) всё равно принимает фокус — стрелки
 * не перепрыгивают через него, — но не нажимается и выглядит обычным.
 *
 * Для экранного диктора весь элемент — одна кнопка с подписью [label]. Если
 * элемент входит в список вариантов ([inChoiceList]), диктор ещё говорит,
 * выбран ли он (по [tinted]).
 *
 * [content] получает, есть ли сейчас фокус, чтобы выбрать цвет текста.
 */
@Composable
fun TvPressable(
    label: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    look: TvFocusLook = TvFocusLook.Tile,
    tinted: Boolean = false,
    inChoiceList: Boolean = false,
    available: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onKeyDown: (Key) -> Boolean = { false },
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val grow by animateFloatAsState(
        targetValue = if (focused && available) look.grow else 1f,
        label = "tv-pressable-grow",
    )
    val fill = when {
        !available -> TvColors.Card
        focused -> TvColors.FocusFill
        tinted -> TvColors.Chosen
        else -> TvColors.Card
    }

    Box(
        modifier = modifier
            // Подросший элемент рисуется поверх соседей.
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = grow
                scaleY = grow
                shape = look.shape
                clip = true
            }
            .background(fill)
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
            .onFocusChanged { change ->
                focused = change.isFocused
                if (change.isFocused) onFocused()
            }
            .tvRemoteInput(available, onPress, onKeyDown)
            // Последним: заменяет собой всё, что сообщили внутренние части.
            .tvAnnouncement(label, available, if (inChoiceList) tinted else null, onPress),
    ) {
        content(focused)
    }
}

/**
 * Обработка пульта: нажатия других кнопок сначала отдаются [onKeyDown],
 * «ОК» при отпускании вызывает [onPress]. Недоступный элемент только держит фокус.
 */
private fun Modifier.tvRemoteInput(
    available: Boolean,
    onPress: () -> Unit,
    onKeyDown: (Key) -> Boolean,
): Modifier {
    if (!available) return focusable()
    return onKeyEvent { event ->
        when (event.type) {
            KeyEventType.KeyDown -> onKeyDown(event.key)
            KeyEventType.KeyUp -> (event.key in PressKeys).also { pressed -> if (pressed) onPress() }
            else -> false
        }
    }
        .clickable(role = null, onClick = onPress)
        .focusable()
}

/** Что слышит экранный диктор: одна кнопка с подписью, без отдельных надписей внутри. */
private fun Modifier.tvAnnouncement(
    label: String,
    available: Boolean,
    chosen: Boolean?,
    onPress: () -> Unit,
): Modifier = clearAndSetSemantics {
    contentDescription = label
    role = Role.Button
    if (chosen != null) selected = chosen
    if (available) onClick { onPress(); true } else disabled()
}

/** Надпись «SMPL|TV», как на значке приложения, в цветах дизайн-системы. */
@Composable
fun TvWordmark(fontSize: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WordmarkPart("SMPL", TvColors.Ink, fontSize, FontWeight.Bold)
        WordmarkPart(" | ", TvColors.InkFaint, fontSize, null)
        WordmarkPart("TV", TvColors.Brand, fontSize, FontWeight.Bold)
    }
}

@Composable
private fun WordmarkPart(text: String, color: Color, fontSize: TextUnit, weight: FontWeight?) {
    androidx.tv.material3.Text(
        text = text,
        color = color,
        fontSize = fontSize,
        fontWeight = weight,
        fontFamily = TvFonts.Ui,
    )
}
