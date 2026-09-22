package com.yfuse.core.navigation

/**
 * Prevents repeated UI events from starting overlapping navigation mutations.
 *
 * [complete] releases the flight after the destination route is removed (or when its push
 * fails), not when the child is merely constructed. Child construction can precede the
 * observable active-stack update, and releasing there leaves a short duplicate-push window.
 */
internal class SingleFlightNavigationGuard<T> {
    private var pending: T? = null

    fun tryBegin(
        target: T,
        active: T?,
    ): Boolean {
        if (pending != null || active != null) return false
        pending = target
        return true
    }

    fun complete(target: T) {
        if (pending == target) pending = null
    }
}
