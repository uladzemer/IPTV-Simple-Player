package ru.iptvsimple.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.iptvsimple.engine.settings.TvPreferences
import ru.iptvsimple.engine.settings.TvSettings

/**
 * Включает плеер, когда приставка загрузилась. На Android 10 и новее система
 * разрешает это, только если приложению дано право «поверх других окон» —
 * экран «Настройки» помогает его выдать.
 */
class TvBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        val app = context.applicationContext
        // Настройки читаются с диска, поэтому держим приёмник живым до конца чтения.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (startOnBootEnabled(app)) openPlayer(app)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun startOnBootEnabled(context: Context): Boolean =
        try {
            TvSettings.from(context).current().startOnBoot
        } catch (failure: Exception) {
            Log.w(TAG, "не удалось прочитать настройки, беру значение по умолчанию", failure)
            TvPreferences.DEFAULT_START_ON_BOOT
        }

    private fun openPlayer(context: Context) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (failure: RuntimeException) {
            // Без права «поверх других окон» система запуск не пропустит.
            Log.w(TAG, "система не дала открыть плеер после загрузки", failure)
        }
    }

    private companion object {
        const val TAG = "TvBoot"

        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
