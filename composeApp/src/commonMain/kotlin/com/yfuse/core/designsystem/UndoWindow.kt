package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * 先做，给 5 秒撤销, for one screen's store.
 *
 * The change shows at once; what it means on the server waits here until its toast leaves, and
 * [commit] is what makes it real. At most one change waits at a time: doing something new commits
 * the previous one, which is also why [ActionToast] retires an older undo when a newer notice
 * arrives. The protocol, in the order a store sees it:
 *
 * - The action: [hold] the change; post a toast whose [ToastAction] undoes this change by its
 *   identity.
 * - 撤销: [undo] with that identity — null means the change has already gone, and nothing is
 *   restored — then put the screen back.
 * - The toast leaving (timed out, swiped, the app backgrounded, the screen closed): [settle].
 *
 * The deadline is the toast's: [ActionToast] counts down [TOAST_UNDO_WINDOW_MS] under its 撤销
 * (longer when an accessibility service asks for more), so the ring the user watches and the moment
 * the change is committed cannot drift apart. A clock of its own here would be a second one.
 */
class UndoWindow<T : Any>(
    private val commit: (T) -> Unit,
) {
    private var pending: T? = null

    /** What is waiting now, if anything. */
    val current: T? get() = pending

    /** Holds [change], and commits the change it displaced. That commit already sees [change] waiting. */
    fun hold(change: T) {
        val displaced = pending
        pending = change
        displaced?.let(commit)
    }

    /** Takes back the waiting change if [matches] says it is the one being undone; nothing is committed. */
    fun undo(matches: (T) -> Boolean): T? {
        val waiting = pending?.takeIf(matches) ?: return null
        pending = null
        return waiting
    }

    /** The toast has left: commits the waiting change, if there is one. */
    fun settle() {
        release()?.let(commit)
    }

    /**
     * Hands the waiting change over without committing it, for a caller whose own write already
     * carries it; null when there is none.
     */
    fun release(): T? {
        val waiting = pending
        pending = null
        return waiting
    }
}

/**
 * An [UndoWindow] for a screen that holds its changes itself, committed with the latest [commit].
 * Leaving the screen is the toast leaving too: nothing may stay held behind a closed screen, so
 * the window settles when it leaves the composition, or when [keys] replace it.
 */
@Composable
fun <T : Any> rememberUndoWindow(
    vararg keys: Any?,
    commit: (T) -> Unit,
): UndoWindow<T> {
    val latestCommit by rememberUpdatedState(commit)
    val window = remember(*keys) { UndoWindow<T> { latestCommit(it) } }
    DisposableEffect(window) {
        onDispose { window.settle() }
    }
    return window
}
