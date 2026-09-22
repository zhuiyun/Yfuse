package com.yfuse.core.sync

/**
 * Small, testable state machine behind WatchTogetherClient's socket ownership.
 * A generation changes before a new start/leave, so a delayed finally from an old socket can
 * never clear the owner installed by its replacement.
 */
internal class WatchConnectionOwnership<T> {
    private val lock = Any()
    private var generation = 0L
    private var owner: T? = null

    fun advance(): Long =
        synchronized(lock) {
            owner = null
            ++generation
        }

    fun claim(
        candidateGeneration: Long,
        candidate: T,
    ): Boolean =
        synchronized(lock) {
            if (generation != candidateGeneration) return@synchronized false
            owner = candidate
            true
        }

    fun clear(candidateGeneration: Long) =
        synchronized(lock) {
            if (generation == candidateGeneration) owner = null
        }

    fun current(): T? = synchronized(lock) { owner }

    fun isCurrent(candidateGeneration: Long): Boolean =
        synchronized(lock) {
            generation == candidateGeneration
        }
}
