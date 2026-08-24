package com.yfuse.core.navigation

/** Prevents repeated UI events from starting overlapping navigation mutations. */
internal class SingleFlightNavigationGuard<T> {
    private var pending: T? = null

    fun tryBegin(
        target: T,
        active: T?,
    ): Boolean {
        if (pending != null || active == target) return false
        pending = target
        return true
    }

    fun complete(target: T) {
        if (pending == target) pending = null
    }
}
