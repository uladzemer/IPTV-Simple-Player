package ru.iptvsimple.tv

import kotlin.test.Test
import kotlin.test.assertEquals

class TvListScrollTest {
    // Список высотой 500, строка 50: запас до края — 20 % высоты, то есть 100.
    private fun distance(offset: Float) = TvListScroll.calculateScrollDistance(offset, 50f, 500f)

    @Test
    fun `строка посреди списка — список стоит`() {
        assertEquals(0f, distance(100f))
        assertEquals(0f, distance(200f))
        assertEquals(0f, distance(350f))
    }

    @Test
    fun `строка у нижнего края — список сдвигается ровно до запаса`() {
        assertEquals(50f, distance(400f))
    }

    @Test
    fun `строка у верхнего края — список сдвигается назад`() {
        assertEquals(-60f, distance(40f))
    }

    @Test
    fun `строка выше половины списка — запас уменьшается, а не дёргает список`() {
        assertEquals(0f, TvListScroll.calculateScrollDistance(10f, 480f, 500f))
    }
}
