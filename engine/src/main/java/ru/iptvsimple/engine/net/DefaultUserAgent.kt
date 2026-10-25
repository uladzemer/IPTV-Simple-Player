package ru.iptvsimple.engine.net

import javax.inject.Qualifier

/** Строка User-Agent по умолчанию для загрузки плейлистов и потоков. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultUserAgent
