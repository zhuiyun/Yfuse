package com.yfuse.core.cast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** A receiver address alone cannot distinguish two loads on the same device. */
internal data class CastSessionToken(
    val deviceId: String?,
    val revision: Long,
) {
    fun matches(state: CastState): Boolean =
        state.activeDeviceId == deviceId && state.sessionRevision == revision && state.termination == null
}

internal fun CastState.castSessionToken(): CastSessionToken = CastSessionToken(activeDeviceId, sessionRevision)

/**
 * Restoring the previous media is another generation, not a return to its old request token.
 * Neither old-media receipts nor receipts from the failed load prove output for this generation.
 */
internal fun restoreCastSessionAfterFailedLoad(
    previous: CastState,
    failed: CastState,
    message: String,
): CastState {
    val latestRevision = maxOf(previous.sessionRevision, failed.sessionRevision)
    check(latestRevision < Long.MAX_VALUE) { "Cast session revision exhausted" }
    val revision = latestRevision + 1L
    val restored = if (previous.hasActiveSession) previous else failed.commandFailed(message)
    return restored.copy(
        sessionRevision = revision,
        capabilities =
            restored.capabilities.copy(
                receiverConfirmed = false,
                dolbyVision = CastCapability.Unknown,
                dolbyAtmos = CastCapability.Unknown,
                requestedMedia = CastCapability.Unknown,
            ),
        outputEvidence = CastOutputEvidence(sessionRevision = revision),
        error = message,
    )
}

/** Check ownership after I/O as well as before it; cancellation is never a receiver failure. */
internal suspend fun <T> readDlnaSessionResult(
    token: CastSessionToken,
    currentState: () -> CastState,
    read: suspend () -> T,
): Result<T>? {
    currentCoroutineContext().ensureActive()
    if (!token.matches(currentState())) return null
    val result =
        try {
            Result.success(read())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    currentCoroutineContext().ensureActive()
    return result.takeIf { token.matches(currentState()) }
}

/** Return the first confirmed snapshot, without asking a healthy receiver redundant questions. */
internal suspend fun <T> awaitDlnaConfirmation(
    attempts: Int,
    delayMs: Long,
    read: suspend () -> T,
    accepted: (T) -> Boolean,
): T? {
    require(attempts > 0 && delayMs >= 0L)
    repeat(attempts) { attempt ->
        val snapshot = read()
        if (accepted(snapshot)) return snapshot
        if (attempt + 1 < attempts) delay(delayMs)
    }
    return null
}
