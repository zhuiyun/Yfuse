package com.yfuse.core2.android

import java.io.IOException

internal class YUpstreamHttpException(val statusCode: Int) : IOException("HTTP $statusCode")

/** Only decorates logs in the transport suite. The proxy suite compiles the real mapper instead. */
internal fun Throwable.mediaHttpStatus(): Int? = when (this) {
    is YRangeReadException -> statusCode
    is AndroidRangeResponseException -> statusCode
    else -> null
}
