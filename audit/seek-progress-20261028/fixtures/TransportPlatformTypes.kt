package com.yfuse.core2.android

import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YTransportFailureKind
import java.io.IOException

/** Production HTTP engines are injected by tests; these unused route types satisfy JVM linkage. */
internal abstract class AndroidAdaptiveHttpMediaTransport : YMediaTransport {
    fun onRangeTimeout() = Unit
}
internal open class AndroidRangeResponseException(
    val failureKind: YTransportFailureKind,
    val statusCode: Int,
    val expectedRangeStart: Long,
    val acceptedRangeStart: Long?,
    safeMessage: String,
) : IOException(safeMessage)
internal enum class MediaRedirectFailureReason { MissingLocation, InvalidLocation, TooManyRedirects, CleartextDisallowed }
internal class AndroidMediaRedirectException(
    statusCode: Int,
    expectedRangeStart: Long,
    val reason: MediaRedirectFailureReason,
    val redirectCount: Int,
    val locationPresent: Boolean,
) : AndroidRangeResponseException(YTransportFailureKind.RedirectRejected, statusCode, expectedRangeStart, null, "Rejected redirect")
