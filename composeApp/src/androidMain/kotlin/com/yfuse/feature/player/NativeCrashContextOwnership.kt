package com.yfuse.feature.player

/** A late teardown must never clear another engine's crash context or failure count. */
internal class NativeCrashContextOwnership {
    private var activeOwner: String? = null

    @Synchronized
    fun arm(owner: String) {
        activeOwner = owner
    }

    @Synchronized
    fun disarm(
        owner: String,
        clearContext: () -> Unit,
    ) {
        if (activeOwner != owner) return
        clearContext()
        activeOwner = null
    }
}
