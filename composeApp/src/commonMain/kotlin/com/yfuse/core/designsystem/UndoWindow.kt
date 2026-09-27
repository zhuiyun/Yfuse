package com.yfuse.core.designsystem

/**
 * 先做，给 5 秒撤销, for one screen's store.
 *
 * The change shows at once; what it means on the server waits here until its toast leaves. At
 * most one change waits at a time: doing something new sends the previous one on its way, which
 * is also why [ActionToast] retires an older undo when a newer notice arrives. The protocol, in
 * the order a store sees it:
 *
 * - The action: [hold] the change and commit whatever it hands back; post a toast whose
 *   [ToastAction] undoes this change by its identity.
 * - 撤销: [undo] with that identity — null means the change has already gone, and nothing is
 *   restored — then put the screen back.
 * - The toast leaving (timed out, swiped, the app backgrounded): [release] and commit the result.
 */
class UndoWindow<T : Any> {
    private var pending: T? = null

    /** What is waiting now, if anything. */
    val current: T? get() = pending

    /** Holds [change]; returns the change it displaced, which the caller commits now. */
    fun hold(change: T): T? {
        val displaced = pending
        pending = change
        return displaced
    }

    /** Takes back the waiting change if [matches] says it is the one being undone. */
    fun undo(matches: (T) -> Boolean): T? {
        val waiting = pending?.takeIf(matches) ?: return null
        pending = null
        return waiting
    }

    /** Lets the waiting change go to be committed; null when there is none. */
    fun release(): T? {
        val waiting = pending
        pending = null
        return waiting
    }
}
