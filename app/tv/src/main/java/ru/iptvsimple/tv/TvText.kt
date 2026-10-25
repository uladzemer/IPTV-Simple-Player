package ru.iptvsimple.tv

import java.text.Collator
import java.util.Locale

/**
 * Название для экрана: каждое слово начинается с заглавной буквы
 * («первый канал» → «Первый Канал»). Остальные буквы не трогаем.
 */
internal fun String.capitalizedWords(locale: Locale = Locale.getDefault()): String =
    split(' ').joinToString(separator = " ") { word ->
        word.replaceFirstChar { first -> first.titlecase(locale) }
    }

/**
 * Порядок надписей «как в словаре» для языка [locale]: регистр не важен,
 * буквы с надстрочными знаками стоят там, где их ждёт читатель этого языка.
 * Одинаковые надписи сохраняют исходный порядок (сортировка Kotlin устойчивая).
 */
internal fun <T> byVisibleText(locale: Locale, text: (T) -> String): Comparator<T> {
    val collator = Collator.getInstance(locale)
    collator.strength = Collator.SECONDARY
    collator.decomposition = Collator.CANONICAL_DECOMPOSITION
    return Comparator { a, b -> collator.compare(text(a), text(b)) }
}
