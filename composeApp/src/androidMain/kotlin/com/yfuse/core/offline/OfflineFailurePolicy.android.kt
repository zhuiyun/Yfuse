package com.yfuse.core.offline

import com.yfuse.core.logging.redactDiagnosticText
import java.io.IOException
import java.net.HttpURLConnection

internal class OfflineHttpException(
    val statusCode: Int,
) : IOException("HTTP $statusCode")

internal class OfflineStorageException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class OfflineSubtitleTooLargeException(
    maxBytes: Long,
) : IOException("字幕文件超过 $maxBytes 字节上限")

internal inline fun <T> offlineStorageWrite(block: () -> T): T =
    try {
        block()
    } catch (error: IOException) {
        throw OfflineStorageException("无法写入离线文件，请检查存储空间", error)
    }

internal fun offlineFailureKind(error: Throwable): DownloadFailureKind =
    when (error) {
        is OfflineHttpException ->
            when (error.statusCode) {
                // 403 is a refusal, not an expired login: signing in again cannot change it.
                HttpURLConnection.HTTP_UNAUTHORIZED -> DownloadFailureKind.Authentication
                in 500..599, HttpURLConnection.HTTP_CLIENT_TIMEOUT, 429 -> DownloadFailureKind.Server
                else -> DownloadFailureKind.Source
            }
        is OfflineStorageException -> DownloadFailureKind.Storage
        is IOException -> DownloadFailureKind.Network
        is IllegalStateException -> DownloadFailureKind.Source
        else -> DownloadFailureKind.Unknown
    }

internal fun offlineFailureMessage(
    kind: DownloadFailureKind,
    retry: OfflineRetryPlan?,
    error: Throwable,
): String =
    when (kind) {
        DownloadFailureKind.Authentication -> "登录已失效，请重新登录服务器后重试"
        DownloadFailureKind.Network ->
            if (retry != null) {
                "网络中断，已保留进度，将自动重试（第 ${retry.retryCount}/$MAX_OFFLINE_RETRY_COUNT 次）"
            } else {
                "网络持续不可用，已停止自动重试，可点按手动重试"
            }
        DownloadFailureKind.Server ->
            if (retry != null) {
                "服务器暂时不可用，已保留进度，将自动重试（第 ${retry.retryCount}/$MAX_OFFLINE_RETRY_COUNT 次）"
            } else {
                "服务器持续不可用，已停止自动重试，可点按手动重试"
            }
        DownloadFailureKind.Storage ->
            redactDiagnosticText(
                error.message ?: "存储空间不足，请清理空间后重试",
            )
        DownloadFailureKind.Source ->
            when (error) {
                is OfflineHttpException ->
                    if (error.statusCode == HttpURLConnection.HTTP_FORBIDDEN) {
                        "服务器拒绝了这次下载（HTTP 403），可能未对此账号开放下载"
                    } else {
                        "下载源不可用（HTTP ${error.statusCode}），请检查服务器或媒体源"
                    }
                else -> redactDiagnosticText(error.message ?: "下载源不可用，请重新选择媒体源")
            }
        DownloadFailureKind.Unknown -> redactDiagnosticText(error.message ?: "下载失败，可点按重试")
    }
