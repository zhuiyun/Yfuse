package com.yfuse.core2.network

enum class YTransportFailureKind {
    TransientIo,
    ServerBusy,
    PrematureEof,
    Authorization,
    InvalidRange,
}

/** Bounded retry schedule for validated, idempotent media byte-range reads only. */
fun mediaRangeRetryDelayMs(
    completedRetries: Int,
    failureKind: YTransportFailureKind,
): Long? {
    require(completedRetries >= 0)
    if (
        failureKind == YTransportFailureKind.Authorization ||
        failureKind == YTransportFailureKind.InvalidRange
    ) {
        return null
    }
    return RETRY_DELAYS_MS.getOrNull(completedRetries)
}

private val RETRY_DELAYS_MS = longArrayOf(100L, 300L)
