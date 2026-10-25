package ru.iptvsimple.engine.net

import android.util.Log
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Соединения одного запуска канала.
 *
 * Провайдеры ограничивают число соединений на аккаунт, а зависшее чтение
 * куска иначе держит соединение до таймаута. Поэтому каждый запрос
 * запоминается, пока не закончится, и [cutAll] обрывает их все разом —
 * в том числе те, что уже читают тело ответа (их не видит очередь OkHttp).
 */
class PlaybackConnections(base: OkHttpClient) {

    private val active: MutableSet<Call> = Collections.newSetFromMap(ConcurrentHashMap())

    private val client: OkHttpClient = NetworkSetup.buildPlaybackClient(base)
        .newBuilder()
        .eventListener(
            object : EventListener() {
                override fun callEnd(call: Call) {
                    active.remove(call)
                }

                override fun callFailed(call: Call, ioe: IOException) {
                    active.remove(call)
                }
            },
        )
        .build()

    /** Для `OkHttpDataSource.Factory`: каждый запрос попадает в учёт. */
    val callFactory: Call.Factory = Call.Factory { request ->
        client.newCall(request).also { active.add(it) }
    }

    /** Обрывает все запросы и закрывает свободные соединения. Не из главного потока. */
    fun cutAll() {
        try {
            active.toList().forEach { it.cancel() }
            client.connectionPool.evictAll()
        } catch (failure: RuntimeException) {
            Log.w(TAG, "не удалось оборвать соединения", failure)
        }
    }

    private companion object {
        const val TAG = "TvNet"
    }
}
