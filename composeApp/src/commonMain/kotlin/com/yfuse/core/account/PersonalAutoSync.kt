package com.yfuse.core.account

import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Keeps 个人内容 — 想看, 收藏, 观看历史 and 追剧 — merged with the account's encrypted cloud
 * document, so nobody has to tap 立即同步 for another device to see them.
 *
 * It merges when an account signs in, when the app comes back to the foreground, and shortly
 * after a change another device should see. Playback carrying an existing history entry forward
 * is not such a change: the player reports it every 15 seconds and 播放进度 has a channel of its
 * own, so the new position travels with the next merge. Servers and settings stay manual: a merge
 * carries the cloud's copy of them forward untouched, and an empty cloud waits for someone to
 * upload the first document.
 */
class PersonalAutoSync(
    /** The signed-in account's user id; null while signed out. */
    private val signedInUser: Flow<String?>,
    /** Advances on every change another device should see soon. */
    private val changes: Flow<Long>,
    private val foreground: StateFlow<Boolean>,
    private val merge: suspend () -> Result<Unit>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    /** One merge at a time, whichever trigger asked for it. */
    private val running = Mutex()

    /** Guards every field below. Never held across a suspension point. */
    private val lock = Any()
    private var started = false
    private var scheduled: Job? = null
    private var scheduledAtEpochMs = Long.MAX_VALUE
    private var failureStreak = 0
    private var retryNotBeforeEpochMs = Long.MIN_VALUE
    private var lastSuccessEpochMs = Long.MIN_VALUE

    @Volatile private var userId: String? = null

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        scope.launch {
            signedInUser.distinctUntilChanged().collect { user ->
                synchronized(lock) {
                    userId = user
                    failureStreak = 0
                    retryNotBeforeEpochMs = Long.MIN_VALUE
                    lastSuccessEpochMs = Long.MIN_VALUE
                    scheduled?.cancel()
                    scheduled = null
                    scheduledAtEpochMs = Long.MAX_VALUE
                }
                if (user != null) schedule(0L)
            }
        }
        scope.launch { changes.drop(1).collect { schedule(CHANGE_DELAY_MS) } }
        scope.launch {
            foreground.drop(1).collect { visible ->
                if (!visible) return@collect
                val mergedRecently =
                    synchronized(lock) {
                        lastSuccessEpochMs != Long.MIN_VALUE &&
                            nowEpochMs() - lastSuccessEpochMs < FOREGROUND_REFRESH_MS
                    }
                if (!mergedRecently) schedule(0L)
            }
        }
    }

    /** Merges [delayMs] from now, or sooner if a merge is already due sooner. */
    private fun schedule(delayMs: Long) {
        synchronized(lock) {
            if (userId == null) return
            val at = maxOf(nowEpochMs() + delayMs, retryNotBeforeEpochMs)
            if (scheduled?.isActive == true && scheduledAtEpochMs <= at) return
            scheduled?.cancel()
            scheduledAtEpochMs = at
            scheduled =
                scope.launch {
                    delay(at - nowEpochMs())
                    // Free the slot before merging: a change made meanwhile needs a merge of its own.
                    val self = coroutineContext[Job]
                    synchronized(lock) {
                        if (scheduled === self) {
                            scheduled = null
                            scheduledAtEpochMs = Long.MAX_VALUE
                        }
                    }
                    run()
                }
        }
    }

    private suspend fun run() {
        running.withLock {
            val user = userId ?: return
            val result = merge()
            val backoffMs =
                synchronized(lock) {
                    // Signed out or switched account meanwhile: that change scheduled its own merge.
                    if (userId != user) return
                    if (result.isSuccess) {
                        failureStreak = 0
                        retryNotBeforeEpochMs = Long.MIN_VALUE
                        lastSuccessEpochMs = nowEpochMs()
                        return
                    }
                    failureStreak = (failureStreak + 1).coerceAtMost(MAX_FAILURE_STREAK)
                    personalAutoSyncBackoffMs(failureStreak).also { retryNotBeforeEpochMs = nowEpochMs() + it }
                }
            AppLog.warning(
                category = "account",
                event = "personal_auto_sync_failed",
                message = "Automatic personal sync was deferred",
                throwable = result.exceptionOrNull(),
                attributes = mapOf("backoffMs" to backoffMs.toString()),
            )
            schedule(backoffMs)
        }
    }

    private companion object {
        /** Long enough to fold a burst — an import, a row of 收藏 taps — into one merge. */
        const val CHANGE_DELAY_MS = 10_000L

        /** Switching away and straight back is not worth a merge. */
        const val FOREGROUND_REFRESH_MS = 60_000L
        const val MAX_FAILURE_STREAK = 6
    }
}

/** 30 seconds, doubling with each failure in a row, up to 15 minutes. */
internal fun personalAutoSyncBackoffMs(failureStreak: Int): Long =
    (30_000L shl (failureStreak - 1).coerceIn(0, 5)).coerceAtMost(15 * 60_000L)
