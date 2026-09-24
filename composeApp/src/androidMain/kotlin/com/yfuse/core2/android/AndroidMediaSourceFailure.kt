package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import java.io.IOException

/** Status-only transport evidence; never retain a response body, URL or authentication header. */
internal class YUpstreamHttpException(
    val statusCode: Int,
) : IOException("Media HTTP status $statusCode")

internal fun Throwable.mediaHttpStatus(): Int? =
    generateSequence(this) { it.cause.takeUnless { cause -> cause === it } }
        .take(8)
        .firstNotNullOfOrNull {
            when (it) {
                is YRangeReadException -> it.statusCode
                is AndroidRangeResponseException -> it.statusCode
                is YUpstreamHttpException -> it.statusCode
                else -> null
            }
        }

internal fun Throwable.mediaSourceFailure(): YPlaybackException? {
    val status = mediaHttpStatus()
    if (status != null && status !in 200..299) {
        return YPlaybackException(
            category =
                if (status == 401 ||
                    status == 403
                ) {
                    YPlaybackFailureCategory.Authorization
                } else {
                    YPlaybackFailureCategory.Network
                },
            stage = YPlaybackFailureStage.SourceOpen,
            safeDetail = "Media HTTP status $status",
            cause = this,
        )
    }
    val networkFailure =
        generateSequence(this) { it.cause.takeUnless { cause -> cause === it } }
            .take(8)
            .firstOrNull {
                it is java.net.SocketTimeoutException ||
                    it is java.net.UnknownHostException ||
                    it is java.net.ConnectException ||
                    it is javax.net.ssl.SSLException ||
                    it is YRangeReadException &&
                    it.failureKind in
                    setOf(
                        com.yfuse.core2.network.YTransportFailureKind.TransientIo,
                        com.yfuse.core2.network.YTransportFailureKind.ServerBusy,
                        com.yfuse.core2.network.YTransportFailureKind.PrematureEof,
                    )
            }
    if (networkFailure != null) {
        return YPlaybackException(
            category = YPlaybackFailureCategory.Network,
            stage = YPlaybackFailureStage.SourceOpen,
            safeDetail = "Media transport could not provide source bytes",
            cause = this,
        )
    }
    return (this as? YPlaybackException)?.takeIf {
        it.category in
            setOf(
                YPlaybackFailureCategory.Authorization,
                YPlaybackFailureCategory.Network,
                YPlaybackFailureCategory.Drm,
            )
    }
}

internal fun proxyFailureStatus(failure: Throwable): Pair<Int, String> =
    when (failure.mediaHttpStatus()) {
        401 -> 401 to "Unauthorized"
        403 -> 403 to "Forbidden"
        404 -> 404 to "Not Found"
        410 -> 410 to "Gone"
        429 -> 429 to "Too Many Requests"
        503 -> 503 to "Service Unavailable"
        504 -> 504 to "Gateway Timeout"
        else -> 502 to "Bad Gateway"
    }

/** A source failure cannot be repaired by trying a different decoder or demuxer. */
internal fun YCore2ProbeResult?.sourceSuccessOrThrow(): YCore2ProbeResult.Success? {
    if (this is YCore2ProbeResult.Failure) sourceFailure?.let { throw it }
    return this as? YCore2ProbeResult.Success
}

internal fun skipEnhancedProbeAfterDeadline(
    result: YCore2ProbeResult,
    item: com.yfuse.core2.api.YMediaItem,
): Boolean =
    result is YCore2ProbeResult.Failure &&
        (result.reason == YCore2ProbeFailure.Deadline || result.reason == YCore2ProbeFailure.Busy) &&
        item.drmConfiguration == null &&
        item.sourceHints?.dolbyVision != true
