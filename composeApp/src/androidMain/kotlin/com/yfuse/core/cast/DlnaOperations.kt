package com.yfuse.core.cast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-thread requests for one renderer load. A newer gesture owns the next transport write. */
internal class DlnaSeekCommands {
    private val writes = Mutex()
    var revision = 0L
        private set
    private var targetMs = 0L
    private var requestedAtMs = 0L
    private var pausedPositionMs: Long? = null

    fun select(
        positionMs: Long,
        nowMs: Long,
    ): Long {
        pausedPositionMs = null
        targetMs = positionMs
        requestedAtMs = nowMs
        return ++revision
    }

    fun matchesPausedClock(
        positionMs: Long?,
        nowMs: Long,
        toleranceMs: Long,
    ): Boolean {
        if (revision == 0L || positionMs == null || nowMs < requestedAtMs) return false
        return positionMs - targetMs in -toleranceMs..(nowMs - requestedAtMs + toleranceMs)
    }

    fun observeUntrustedClock(
        status: CastPlaybackStatus,
        positionMs: Long?,
        nowMs: Long,
        toleranceMs: Long,
    ): Long? {
        if (status != CastPlaybackStatus.Paused) {
            pausedPositionMs = null
            return null
        }
        val previous = pausedPositionMs
        if (positionMs != null &&
            matchesPausedClock(positionMs, nowMs, toleranceMs) &&
            (previous == null || kotlin.math.abs(positionMs - previous) <= toleranceMs)
        ) {
            pausedPositionMs = positionMs
            return positionMs
        }
        return null
    }

    fun resumeAnchor(
        state: CastState,
        clockTrusted: Boolean,
    ): Long? =
        pausedPositionMs?.takeIf {
            !clockTrusted &&
                state.status == CastPlaybackStatus.Paused &&
                state.positionConfirmed &&
                state.capabilities.seek == CastCapability.Supported &&
                state.positionMs == it &&
                it > 0L
        }

    suspend fun <T> whenCurrent(
        expected: Long,
        command: suspend () -> T,
    ): T? =
        writes.withLock {
            if (expected != revision) return@withLock null
            command().takeIf { expected == revision }
        }
}

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
