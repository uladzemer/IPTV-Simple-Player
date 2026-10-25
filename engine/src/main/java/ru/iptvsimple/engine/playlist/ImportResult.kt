package ru.iptvsimple.engine.playlist

/** Чем закончилось добавление или обновление плейлиста. */
sealed interface ImportResult {

    data class Success(val playlistUrl: String, val channelCount: Int) : ImportResult

    data class Failure(
        val playlistUrl: String,
        val reason: Reason,
        /** Код ответа сервера, если причина — [Reason.HTTP]. */
        val httpCode: Int? = null,
    ) : ImportResult

    enum class Reason {
        /** Ссылка не http/https или записана с ошибкой. */
        INVALID_URL,

        /** Нет сети, сервер не отвечает или оборвал загрузку. */
        NETWORK,

        /** Сервер ответил ошибкой (404, 500 и т. п.). */
        HTTP,

        /** В файле не нашлось ни одного канала. */
        EMPTY,
    }
}
