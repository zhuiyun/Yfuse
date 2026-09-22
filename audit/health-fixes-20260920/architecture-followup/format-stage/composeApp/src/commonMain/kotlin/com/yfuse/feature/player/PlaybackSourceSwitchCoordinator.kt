package com.yfuse.feature.player

/** Identity of the playback request whose encoder a source switch is allowed to replace. */
internal data class PlaybackSourceSwitchContext(
    val queueRevision: Long,
    val engineGeneration: Int,
    val runtimeSessionGeneration: Int,
    val itemIndex: Int,
    val itemId: String?,
    val serverId: String?,
    val playSessionId: String?,
    val engineIdentity: Any,
)

internal enum class PlaybackSourceSwitchPreparation {
    Ready,
    CleanupRejected,
    Superseded,
}

/** Main-thread owner shared by version and server switches, including while cleanup suspends. */
internal class PlaybackSourceSwitchCoordinator {
    private var generation = 0L
    private var observedEngine: Any? = null
    private var observedItemIndex: Int? = null

    internal data class Request(
        val generation: Long,
        val context: PlaybackSourceSwitchContext,
    )

    fun begin(context: PlaybackSourceSwitchContext): Request = Request(++generation, context)

    fun invalidate() {
        generation++
    }

    /** Retain media transitions; seeks and output-route resets still belong to the same item. */
    fun observePlayback(
        engine: Any,
        state: PlaybackState,
    ) {
        if (observedEngine !== engine || observedItemIndex != state.currentIndex) {
            observedEngine = engine
            observedItemIndex = state.currentIndex
            invalidate()
        }
    }

    suspend fun prepare(
        request: Request,
        currentContext: () -> PlaybackSourceSwitchContext,
        cleanup: suspend () -> Boolean,
    ): PlaybackSourceSwitchPreparation {
        val succeeded = cleanup()
        val current = currentContext()
        return when {
            request.generation != generation ||
                request.context != current ||
                request.context.engineIdentity !== current.engineIdentity ->
                PlaybackSourceSwitchPreparation.Superseded
            succeeded -> PlaybackSourceSwitchPreparation.Ready
            else -> PlaybackSourceSwitchPreparation.CleanupRejected
        }
    }
}
