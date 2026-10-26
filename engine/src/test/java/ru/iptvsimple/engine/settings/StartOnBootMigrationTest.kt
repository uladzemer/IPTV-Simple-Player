package ru.iptvsimple.engine.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartOnBootMigrationTest {

    @Test
    fun `новая установка — автозапуск выключен`() {
        assertFalse(startOnBootAfterUpdate(firstInstallTime = 1_000, lastUpdateTime = 1_000))
        assertFalse(TvPreferences.DEFAULT_START_ON_BOOT)
    }

    @Test
    fun `обновление со старой версии — автозапуск остаётся включённым`() {
        assertTrue(startOnBootAfterUpdate(firstInstallTime = 1_000, lastUpdateTime = 5_000))
    }
}
