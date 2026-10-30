package ru.iptvsimple.tv

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.tv.material3.MaterialTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlin.math.min

/**
 * Единственное окно приложения. Всё содержимое — в [SimpleTvApp]; окно только растягивает
 * картинку на весь экран и задаёт тёмную тему из [TvColors].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Видео занимает весь экран, системные полосы прозрачные.
        val transparentBars = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = transparentBars, navigationBarStyle = transparentBars)
        // Анимации интерфейса идут с нашей скоростью, а не с системной (см. [TvMotion]).
        val recomposer = window.decorView.createLifecycleAwareWindowRecomposer(TvMotion(this), lifecycle)
        setContent(parent = recomposer) { DesignCanvas { SimpleTvTheme { SimpleTvApp() } } }
    }
}

/**
 * Скорость анимаций интерфейса. На приставках часто ускоряют системные
 * анимации (на X96 стояло 0,25×), и Compose ускорял вслед за ними прокрутку
 * списков: она успевала за пару кадров и стояла до следующего шага, строки
 * ехали рывками. Поэтому скорость своя — обычная. Только если анимации
 * в системе выключены совсем (0×), выключаем и свои.
 */
private class TvMotion(context: Context) : MotionDurationScale {
    override val scaleFactor: Float =
        if (Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) 0f
        else 1f
}

/**
 * Пересчитывает плотность так, чтобы экран был ровно [TvSizes.DesignWidth] ×
 * [TvSizes.DesignHeight] dp. Иначе на приставках с нестандартной плотностью
 * (например, Model-X3: 3840×2160 при 640 dpi — всего 960 dp ширины) панели
 * не помещаются и вторая колонка сжимается. Размер шрифта системы сохраняется.
 */
@Composable
private fun DesignCanvas(content: @Composable () -> Unit) {
    val system = LocalDensity.current
    val configuration = LocalConfiguration.current
    val widthPx = configuration.screenWidthDp * system.density
    val heightPx = configuration.screenHeightDp * system.density
    val density = if (widthPx > 0f && heightPx > 0f) {
        val scale = min(widthPx / TvSizes.DesignWidth.value, heightPx / TvSizes.DesignHeight.value)
        Density(scale, system.fontScale)
    } else {
        system
    }
    CompositionLocalProvider(LocalDensity provides density, content = content)
}

/**
 * Тёмная тема TV Material из нашей палитры и фон под всем содержимым окна.
 * Списки прокручиваются за фокусом по [TvListScroll].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SimpleTvTheme(content: @Composable () -> Unit) {
    val colors = remember { tvMaterialColors() }
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides TvListScroll) {
            Box(Modifier.background(TvColors.Backdrop)) { content() }
        }
    }
}
