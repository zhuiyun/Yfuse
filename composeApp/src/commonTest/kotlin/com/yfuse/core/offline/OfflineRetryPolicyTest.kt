package com.yfuse.core.offline

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfflineRetryPolicyTest {
    @Test
    fun legacyItemsDecodeWithSafeRetryDefaults() {
        val item =
            Json.decodeFromString<OfflineMedia>(
                """{"id":"server#item","serverId":"server","itemId":"item","title":"片名"}""",
            )

        assertEquals(0, item.retryCount)
        assertEquals(0L, item.nextRetryAt)
        assertNull(item.lastFailureKind)
    }

    @Test
    fun recoverableFailuresUseBoundedExponentialDelay() {
        val nowMs = 1_000L
        val expectedDelays = listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L)

        expectedDelays.forEachIndexed { previousRetryCount, expectedDelay ->
            val plan =
                planOfflineRetry(
                    failureKind = DownloadFailureKind.Network,
                    currentRetryCount = previousRetryCount,
                    nowMs = nowMs,
                )

            assertEquals(previousRetryCount + 1, plan?.retryCount)
            assertEquals(nowMs + expectedDelay, plan?.nextRetryAt)
        }
        assertNull(
            planOfflineRetry(
                failureKind = DownloadFailureKind.Server,
                currentRetryCount = MAX_OFFLINE_RETRY_COUNT,
                nowMs = nowMs,
            ),
        )
    }

    @Test
    fun terminalFailuresNeverScheduleAutomaticRetry() {
        val terminalKinds =
            DownloadFailureKind.entries -
                setOf(
                    DownloadFailureKind.Network,
                    DownloadFailureKind.Server,
                )

        terminalKinds.forEach { kind ->
            assertNull(planOfflineRetry(kind, currentRetryCount = 0, nowMs = 1_000L))
        }
    }

    @Test
    fun retryTimestampSaturatesInsteadOfOverflowing() {
        val plan =
            planOfflineRetry(
                failureKind = DownloadFailureKind.Network,
                currentRetryCount = 0,
                nowMs = Long.MAX_VALUE - 10L,
            )

        assertEquals(Long.MAX_VALUE, plan?.nextRetryAt)
    }

    @Test
    fun queueSummarySeparatesRunningPausedFailedAndDelayedWork() {
        val items =
            listOf(
                item("downloading", DownloadStatus.Downloading, downloadedBytes = 20L),
                item("retry", DownloadStatus.Queued, nextRetryAt = 20_000L),
                item("paused", DownloadStatus.Paused),
                item("completed", DownloadStatus.Completed, downloadedBytes = 1_024L),
                item("failed", DownloadStatus.Failed),
            )

        assertEquals(
            OfflineQueueSummary(
                total = 5,
                active = 2,
                paused = 1,
                completed = 1,
                failed = 1,
                retryScheduled = 1,
                completedBytes = 1_024L,
            ),
            summarizeOfflineQueue(items),
        )
    }

    private fun item(
        id: String,
        status: DownloadStatus,
        downloadedBytes: Long = 0L,
        nextRetryAt: Long = 0L,
    ) = OfflineMedia(
        id = id,
        serverId = "server",
        itemId = id,
        title = id,
        status = status,
        downloadedBytes = downloadedBytes,
        nextRetryAt = nextRetryAt,
    )
}
