package com.yfuse.core.widget

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Safe transport failures cross the script boundary without URLs, headers or a JVM cause. */
internal class WidgetRequestException(
    message: String,
) : IOException(message)

internal class WidgetExecutionException(
    message: String,
) : IllegalStateException(message)

internal fun Throwable.isRetryableWidgetReadFailure(): Boolean {
    val causes = generateSequence(this) { it.cause }.take(8).toList()
    if (causes.any { it is SSLException }) return false
    return causes.any { it is SocketException || it is SocketTimeoutException || it is EOFException }
}

internal fun Throwable.widgetNetworkMessage(tmdb: Boolean = false): String {
    val source = if (tmdb) "TMDB 服务" else "内容服务"
    val causes = generateSequence(this) { it.cause }.take(8).toList()
    return when {
        causes.any {
            it is SocketTimeoutException ||
                (it is InterruptedIOException && it.message == "timeout") ||
                it.javaClass.simpleName.contains("Timeout")
        } -> "${source}响应超时，请稍后重试"
        causes.any { it is UnknownHostException } -> "无法连接$source，请检查网络后重试"
        causes.any { it is SSLException } -> "${source}安全连接失败，请稍后重试"
        isRetryableWidgetReadFailure() -> "${source}连接暂时中断，请重试"
        else -> "${source}暂时无法连接，请稍后重试"
    }
}

/** A script error is a short explanation, never a URL, authentication header or stack trace. */
internal fun safeWidgetFailureMessage(raw: String?): String {
    val fallback = "组件内容暂时不可用，请稍后重试"
    var message =
        raw
            .orEmpty()
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty) ?: return fallback
    repeat(4) {
        message = message.replace(Regex("^(?:[A-Za-z_$][A-Za-z0-9_.$]*(?:Exception|Error)|Error):\\s*"), "")
    }
    if (message.isBlank() ||
        message.length > 140 ||
        Regex("(?i)(https?://|wss?://|authorization|bearer |cookie|(?:api[_-]?key|token|password|secret)\\s*[=:])")
            .containsMatchIn(message) ||
        message.contains("java.") ||
        message.contains("kotlin.") ||
        message.contains("\tat ")
    ) {
        return fallback
    }
    return message
}
