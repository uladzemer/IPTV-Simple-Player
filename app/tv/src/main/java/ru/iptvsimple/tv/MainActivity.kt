package ru.iptvsimple.tv

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.tv.material3.MaterialTheme
import dagger.hilt.android.AndroidEntryPoint

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
        setContent { SimpleTvTheme { SimpleTvApp() } }
    }
}

/** Тёмная тема TV Material из нашей палитры и фон под всем содержимым окна. */
@Composable
private fun SimpleTvTheme(content: @Composable () -> Unit) {
    val colors = remember { tvMaterialColors() }
    MaterialTheme(colorScheme = colors) {
        Box(Modifier.background(TvColors.Backdrop)) { content() }
    }
}
