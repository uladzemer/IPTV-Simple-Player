package ru.iptvsimple.tv

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
 * подрастает («приподнимается» над соседями, под ним появляется тень),
 * какой формы его подложка и какого цвета она без фокуса.
 */
@Immutable
class TvFocusLook(val grow: Float, val shape: Shape, val rest: Color) {
    companion object {
        /** Отдельная плитка: поднимается сильнее всего. */
        val Tile = TvFocusLook(grow = 1.08f, shape = TvShapes.Large, rest = TvColors.Rest)

        /** Строка длинного списка: заметный подъём, соседи слегка перекрываются. */
        val ListRow = TvFocusLook(grow = 1.06f, shape = TvShapes.Large, rest = TvColors.Rest)

        /** Строка меню или боковой панели: без фокуса прозрачна, в фокусе чуть поднимается. */
        val MenuRow = TvFocusLook(grow = 1.04f, shape = TvShapes.Medium, rest = Color.Transparent)
    }
}

/** Подъём в фокусе — с лёгкой пружиной: элемент чуть «проскакивает» и встаёт на место. */
private val FocusGrowSpring = spring<Float>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)

/**
 * Как список прокручивается за фокусом пульта. Системный вариант для ТВ
 * держит строку в фокусе на трети высоты и сдвигает весь список на каждое
 * нажатие; при зажатой стрелке сдвиги перебивают друг друга, и строки дёргаются.
 * Здесь список стоит, пока строка в фокусе далеко от края, и сдвигается ровно
 * настолько, чтобы до края оставался запас [EDGE_FRACTION] высоты списка.
 */
@OptIn(ExperimentalFoundationApi::class)
internal object TvListScroll : BringIntoViewSpec {
    private const val EDGE_FRACTION = 0.2f

    /**
     * Мягкая пружина: один сдвиг длится дольше шага зажатой стрелки, поэтому
     * следующий шаг подхватывает движение на ходу и список едет ровно. Со
     * стандартной жёсткой пружиной список успевал доехать за пару кадров
     * и стоял до следующего шага — получалась «лесенка» из рывков.
     */
    override val scrollAnimationSpec: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Строка выше списка целиком не помещается в запас — тогда запас меньше.
        val margin = (containerSize * EDGE_FRACTION)
            .coerceAtMost(((containerSize - size) / 2f).coerceAtLeast(0f))
        val beforeStart = offset - margin
        val afterEnd = offset + size + margin - containerSize
        return when {
            beforeStart < 0f -> beforeStart
            afterEnd > 0f -> afterEnd
            else -> 0f
        }
    }
}

/** Шаг зажатой стрелки в списке: не чаще, чем список успевает плавно сдвинуться. */
private const val REPEAT_STEP_MILLIS = 90L

/**
 * Зажатая стрелка ▲/▼ двигает фокус не чаще раза в [REPEAT_STEP_MILLIS].
 * Пульт присылает повторы быстрее, чем список успевает прокрутиться, и фокус
 * уходил за край, а список догонял его рывками. Первое нажатие проходит сразу.
 */
@Composable
fun Modifier.tvSteadyRepeat(): Modifier {
    // Время последнего шага переживает перерисовки: они идут на каждом шаге фокуса.
    val lastStepAt = remember { longArrayOf(0L) }
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        if (event.key != Key.DirectionUp && event.key != Key.DirectionDown) return@onPreviewKeyEvent false
        val native = event.nativeKeyEvent
        val tooSoon = native.repeatCount > 0 && native.eventTime - lastStepAt[0] < REPEAT_STEP_MILLIS
        if (!tooSoon) lastStepAt[0] = native.eventTime
        tooSoon
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
        animationSpec = FocusGrowSpring,
        label = "tv-pressable-grow",
    )
    val fill = when {
        !available -> look.rest
        focused -> TvColors.FocusFill
        tinted -> TvColors.Chosen
        else -> look.rest
    }

    Box(
        modifier = modifier
            // Подросший элемент рисуется поверх соседей.
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = grow
                scaleY = grow
                // Тень растёт вместе с подъёмом и пропадает, когда элемент опустился.
                val lift = if (look.grow > 1f) ((grow - 1f) / (look.grow - 1f)).coerceIn(0f, 1f) else 0f
                shadowElevation = TvSizes.FocusLift.toPx() * lift
                ambientShadowColor = TvColors.Shadow
                spotShadowColor = TvColors.Shadow
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
