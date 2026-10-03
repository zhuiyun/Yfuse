package com.yfuse.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One kind of request of which only the newest may publish: a page's load, a source switch, the
 * next page of a grid.
 *
 * Each new request outdates the ones before it, and its [Ticket] says whether it is still the
 * newest. Cancelling the older job is not enough on its own: a repository that answers at the
 * moment of cancellation, or a callback already on its way, still reaches the code after the
 * suspension point, so a result is checked against its ticket before it is published. The older
 * request is outdated before its job is cancelled, so even a cancellation handled on the spot (an
 * unconfined dispatcher runs it inside [next]) already finds itself stale.
 *
 * Used from one thread, as a store's executor is.
 */
class LatestWins(
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    /** Requests started or outdated so far; logs name it to tell one load from the next. */
    var generation = 0L
        private set

    /** The newest request, for work launched elsewhere that must stop publishing once it is not. */
    val current: Ticket get() = Ticket(generation)

    /** Whether the newest request's job is still running. */
    val isActive: Boolean get() = job?.isActive == true

    /** Outdates every earlier request and cancels its job; returns the ticket of the one replacing it. */
    fun next(): Ticket {
        generation++
        job?.cancel()
        return current
    }

    /** Outdates every earlier request but lets its job run on: it finishes, and publishes nothing. */
    fun outdate(): Ticket {
        generation++
        return current
    }

    /** Outdates and cancels whatever is in flight, with nothing new to start. */
    fun cancel() {
        next()
    }

    /**
     * Runs [block] as the job of [ticket]'s request. By default that is a new request, which
     * cancels the previous job; a caller with work to do in between takes its ticket from [next]
     * (or [outdate]) first and passes it here.
     */
    fun launch(
        ticket: Ticket = next(),
        block: suspend CoroutineScope.(Ticket) -> Unit,
    ): Job = scope.launch { block(ticket) }.also { job = it }

    /**
     * Ends [ticket]'s request from inside its own job. True while it is still the newest: its job
     * stops counting as [isActive] at once, and the caller runs whatever ends the request. False
     * once a newer request owns that.
     */
    fun finish(ticket: Ticket): Boolean {
        if (!ticket.isCurrent) return false
        job = null
        return true
    }

    /** Waits for the newest request's job, if there is one. */
    suspend fun join() {
        job?.join()
    }

    /** One request's claim to publish; it lapses as soon as a newer request starts. */
    inner class Ticket internal constructor(
        val generation: Long,
    ) {
        val isCurrent: Boolean get() = generation == this@LatestWins.generation
    }
}
