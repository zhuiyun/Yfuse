package com.yfuse.core.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal class OfflineCommandRejectedException : IllegalStateException("Offline command queue is full or stopped")

/** A bounded FIFO: callers never execute disk work, and one failed command cannot kill the consumer. */
internal class OfflineCommandQueue(
    scope: CoroutineScope,
    capacity: Int = 64,
    private val onFailure: (Throwable) -> Unit,
) {
    private class Command(
        val action: () -> Unit,
        val completion: CompletableDeferred<Unit>? = null,
    )

    private val commands =
        Channel<Command>(capacity) { command ->
            command.completion?.cancel(CancellationException("Offline command queue stopped"))
        }

    init {
        scope
            .launch {
                for (command in commands) {
                    try {
                        command.action()
                        command.completion?.complete(Unit)
                    } catch (cancelled: CancellationException) {
                        command.completion?.cancel(cancelled)
                        throw cancelled
                    } catch (error: Exception) {
                        command.completion?.completeExceptionally(error)
                        onFailure(error)
                    }
                }
            }.invokeOnCompletion { commands.cancel() }
    }

    fun submit(action: () -> Unit): Boolean {
        if (commands.trySend(Command(action)).isSuccess) return true
        onFailure(OfflineCommandRejectedException())
        return false
    }

    /** A selection occupies one slot; one item's storage failure must not discard later items. */
    fun <T> submitBatch(
        items: List<T>,
        beforeBatch: () -> Unit = {},
        action: (T) -> Unit,
    ): Boolean {
        if (items.isEmpty()) return true
        val snapshot = items.distinct()
        return submit {
            beforeBatch()
            snapshot.forEach { item ->
                try {
                    action(item)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onFailure(error)
                }
            }
        }
    }

    /** Workers suspend behind accepted UI commands; a stopped consumer fails instead of hanging. */
    suspend fun awaitPending() = execute {}

    suspend fun execute(action: () -> Unit) {
        val completion = CompletableDeferred<Unit>()
        commands.send(Command(action, completion))
        completion.await()
    }
}
