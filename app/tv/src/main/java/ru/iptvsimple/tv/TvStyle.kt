package ru.iptvsimple.tv

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.darkColorScheme

/**
 * Плоская тёмная палитра: спокойные серые поверхности без рамок и свечения.
 * Фокус — светлая заливка с тёмным текстом, выбранный элемент — тихо-синий.
 * Имена говорят, для чего цвет, а не какой он.
 */
object TvColors {
    /** Фон экранов за эфиром и настройками. */
    val Backdrop = Color(0xFF111316)
    /** Поле ввода без фокуса — чуть светлее фона. */
    val FieldIdle = Color(0xFF16191D)
    /** Подложка строк, плиток и панелей. */
    val Card = Color(0xFF1D2024)
    /** Подложка, поднятая над карточкой: поле ввода в фокусе, дорожка переключателя. */
    val CardRaised = Color(0xFF272A2F)
    /** Заливка элемента в фокусе пульта и текст на ней. */
    val FocusFill = Color(0xFFE3E3E6)
    val OnFocusFill = Color(0xFF16181B)
    /** Выбранный (текущий) элемент без фокуса и текст на нём. */
    val Chosen = Color(0xFF2A3546)
    val OnChosen = Color(0xFFC6DAFF)
    /** Фирменный голубой: «TV» в знаке, отметки выбора. */
    val Brand = Color(0xFFA8C7FA)
    /** Звезда избранного. */
    val Star = Color(0xFFF6C344)
    /** Предупреждение: например, «Нужно разрешение». */
    val Warning = Color(0xFFF2B8B5)
    /** Текст: основной, второстепенный и самый тихий. */
    val Ink = Color(0xFFE6E6E9)
    val InkSoft = Color(0xFFB4B7BC)
    val InkFaint = Color(0xFF85898F)
    /** Полупрозрачная подложка надписей поверх видео. */
    val Veil = Color(0xE6111316)
}

/** Отступы по сетке 4dp. */
object TvSpacing {
    val XSmall = 4.dp
    val Small = 8.dp
    val Compact = 12.dp
    val Medium = 16.dp
    val Comfortable = 20.dp
    val Large = 24.dp
    val XLarge = 32.dp
    val Wide = 40.dp
    val XXLarge = 48.dp
}

/** Размеры текста, чтобы читать с дивана, метра с трёх. */
object TvType {
    /** Цифры набираемого номера канала. */
    val Digits = 56.sp
    val Display = 40.sp
    val Headline = 30.sp
    val Title = 24.sp
    /** Названия в списках каналов и групп. */
    val BodyLarge = 22.sp
    val Body = 20.sp
    /** Подписи рядом с названием: число каналов, заголовки разделов панели. */
    val Caption = 18.sp
    val Label = 16.sp
}

/** Размеры элементов: строки, значки, панели. */
object TvSizes {
    /** Значки в строках меню и настроек. */
    val Icon = 28.dp
    /** Звезда избранного рядом с названием в эфире. */
    val IconLarge = 40.dp
    /** Логотип канала в списке и в карточке эфира. */
    val LogoSmall = 48.dp
    val LogoLarge = 72.dp

    /** Наименьшая высота строк разного вида: от строки плейлиста до выбора номера. */
    val RowShort = 48.dp
    val RowCompact = 56.dp
    val Row = 64.dp
    val RowTall = 72.dp
    val RowStepper = 88.dp

    /** Колонка номера канала в списке. */
    val NumberColumn = 56.dp
    /** Колонка групп в панели каналов. */
    val GroupColumn = 340.dp
    /** Меню канала (▶). */
    val ChannelMenu = 440.dp
    /** Боковая панель вариантов в настройках. */
    val SidePanel = 480.dp
    /** Наибольшая ширина надписи поверх видео. */
    val MessageMax = 720.dp
    /** Список строк настроек. */
    val SettingsList = 760.dp

    /** Внутренний отступ поля ввода сверху и снизу. */
    val FieldPaddingVertical = 18.dp

    /** Переключатель: дорожка, кружок и его положение во «вкл» и «выкл». */
    val ToggleTrackWidth = 48.dp
    val ToggleTrackHeight = 28.dp
    val ToggleKnob = 20.dp
    val ToggleKnobOn = 24.dp
    val ToggleKnobOff = 4.dp
}

/** Скругления углов. */
object TvShapes {
    val Small = RoundedCornerShape(8.dp)
    val Medium = RoundedCornerShape(12.dp)
    val Large = RoundedCornerShape(16.dp)
}

/**
 * Шрифт — системный: у приставок он всегда есть и хорошо читается с кириллицей.
 * Веса (обычный, средний, полужирный, жирный) задаются в местах использования.
 */
object TvFonts {
    val Ui: FontFamily = FontFamily.SansSerif
}

/**
 * Тема библиотеки TV Material. Из её компонентов мы берём только Text и Icon,
 * а цвета им задаём сами, поэтому здесь лишь фон, поверхность и акцент —
 * на случай, если компонент возьмёт цвет из темы.
 */
fun tvMaterialColors(): ColorScheme = darkColorScheme(
    background = TvColors.Backdrop,
    onBackground = TvColors.Ink,
    surface = TvColors.Card,
    onSurface = TvColors.Ink,
    primary = TvColors.FocusFill,
    onPrimary = TvColors.OnFocusFill,
)

/** Что сейчас на экране: заставка, настройки или эфир. */
enum class TvPage { Splash, Settings, Watching }
