package com.yfuse.watch

private const val DEFAULT_PENDING_FLOOR = 8
private const val DEFAULT_PENDING_PER_IP = 4

/**
 * Bounds both handshakes and established sockets. A lease first consumes the global and network
 * identity budgets, then binds the authenticated account before any per-socket watchdog starts.
 * Every mutation is under one small lock and [Lease.close] is idempotent, so normal cleanup and the
 * WebSocket coroutine's completion callback can safely converge on the same release path.
 */
internal class WatchConnectionGate(
    private val globalLimit: Int,
    private val perIpLimit: Int,
    private val perAccountLimit: Int,
    /**
     * Sockets that have not authenticated yet get their own, smaller pool: without one, a few
     * addresses holding unauthenticated connections open could fill the whole global quota.
     */
    private val pendingLimit: Int = maxOf(globalLimit / 4, minOf(globalLimit, DEFAULT_PENDING_FLOOR)),
    private val pendingPerIpLimit: Int = minOf(perIpLimit, DEFAULT_PENDING_PER_IP),
) {
    private val lock = Any()
    private var active = 0
    private var pending = 0
    private val activeByIp = mutableMapOf<String, Int>()
    private val pendingByIp = mutableMapOf<String, Int>()
    private val activeByAccount = mutableMapOf<String, Int>()

    init {
        require(globalLimit > 0)
        require(perIpLimit in 1..globalLimit)
        require(perAccountLimit in 1..globalLimit)
        require(pendingLimit in 1..globalLimit)
        require(pendingPerIpLimit in 1..perIpLimit)
    }

    fun tryAcquire(clientIp: String): Lease? =
        synchronized(lock) {
            val ipActive = activeByIp[clientIp] ?: 0
            val ipPending = pendingByIp[clientIp] ?: 0
            if (
                active + pending >= globalLimit ||
                ipActive + ipPending >= perIpLimit ||
                pending >= pendingLimit ||
                ipPending >= pendingPerIpLimit
            ) {
                return@synchronized null
            }
            pending++
            pendingByIp[clientIp] = ipPending + 1
            Lease(clientIp)
        }

    internal inner class Lease internal constructor(
        private val clientIp: String,
    ) : AutoCloseable {
        private var accountUserId: String? = null
        private var promoted = false
        private var released = false

        /** Moves the socket from the pending pool to the active pool once its account is known. */
        fun tryBindAccount(userId: String): Boolean =
            synchronized(lock) {
                check(!released) { "connection lease is already released" }
                val current = accountUserId
                if (current != null) return@synchronized current == userId
                val accountCount = activeByAccount[userId] ?: 0
                if (accountCount >= perAccountLimit) return@synchronized false
                activeByAccount[userId] = accountCount + 1
                accountUserId = userId
                promoteLocked()
                true
            }

        /** Test-only unauthenticated mode: the socket is admitted without an account. */
        fun promote() {
            synchronized(lock) {
                check(!released) { "connection lease is already released" }
                promoteLocked()
            }
        }

        private fun promoteLocked() {
            if (promoted) return
            promoted = true
            pending--
            decrement(pendingByIp, clientIp)
            active++
            activeByIp[clientIp] = (activeByIp[clientIp] ?: 0) + 1
        }

        override fun close() {
            synchronized(lock) {
                if (released) return
                released = true
                if (promoted) {
                    active--
                    decrement(activeByIp, clientIp)
                } else {
                    pending--
                    decrement(pendingByIp, clientIp)
                }
                accountUserId?.let { decrement(activeByAccount, it) }
            }
        }
    }

    private fun decrement(
        counts: MutableMap<String, Int>,
        key: String,
    ) {
        val remaining = checkNotNull(counts[key]) - 1
        if (remaining == 0) counts.remove(key) else counts[key] = remaining
    }
}
