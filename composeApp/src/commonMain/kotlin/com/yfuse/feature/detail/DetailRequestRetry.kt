package com.yfuse.feature.detail

import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal suspend fun <T> retryTransientDetailRequest(
    event: String,
    attributes: Map<String, String>,
    stillCurrent: () -> Boolean,
    request: suspend () -> Result<T>,
): Result<T> {
    var attempt = 1
    while (true) {
        val result =
            try {
                request()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
        val failure = result.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (
            result.isSuccess ||
            attempt >= SOURCE_SELECTION_MAX_ATTEMPTS ||
            failure?.isTransientSourceFailure() != true
        ) {
            return result
        }

        val retryDelayMs = SOURCE_SELECTION_RETRY_BASE_DELAY_MS shl (attempt - 1)
        AppLog.info(
            category = "feature.detail",
            event = event,
            message = "Retrying a transient detail request",
            attributes =
                attributes +
                    mapOf(
                        "attempt" to attempt.toString(),
                        "nextAttempt" to (attempt + 1).toString(),
                        "delayMs" to retryDelayMs.toString(),
                    ),
        )
        delay(retryDelayMs)
        if (!stillCurrent()) return result
        attempt++
    }
}
