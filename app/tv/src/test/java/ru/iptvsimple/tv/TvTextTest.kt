package ru.iptvsimple.tv

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class TvTextTest {
    @Test
    fun `каждое слово начинается с заглавной буквы`() {
        val ru = Locale.forLanguageTag("ru")
        assertEquals("Первый Канал HD", "первый канал HD".capitalizedWords(ru))
        assertEquals("", "".capitalizedWords(ru))
        assertEquals("A  B", "a  b".capitalizedWords(ru))
    }

    @Test
    fun `надписи сортируются по правилам языка без учёта регистра`() {
        val ru = Locale.forLanguageTag("ru")
        val sorted = listOf("яблоко", "Вишня", "банан", "Арбуз", "груша")
            .sortedWith(byVisibleText(ru) { it })
        assertEquals(listOf("Арбуз", "банан", "Вишня", "груша", "яблоко"), sorted)

        val sv = Locale.forLanguageTag("sv-SE")
        assertEquals(
            listOf("Zulu", "Åland", "Ängel", "Örebro"),
            listOf("Örebro", "Zulu", "Ängel", "Åland").sortedWith(byVisibleText(sv) { it }),
        )
    }

    @Test
    fun `равные надписи сохраняют исходный порядок`() {
        val items = listOf("b" to 1, "A" to 2, "a" to 3, "B" to 4)
        val sorted = items.sortedWith(byVisibleText(Locale.ENGLISH) { it.first })
        // Регистр различается только на третьем уровне сравнения, который отключён.
        assertEquals(listOf(2, 3, 1, 4), sorted.map { it.second })
    }
}
