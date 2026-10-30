package com.yfuse.feature.player

/** A configured force-window placeholder is not evidence that the selected media has rendered. */
internal class MpvVideoReadinessGate {
    private enum class Phase { AwaitingStart, Loading, Loaded, Started }

    private var phase = Phase.AwaitingStart
    private var requestedEntryId: Long? = null
    private var requestedPath: String? = null

    @Synchronized
    fun onLoadRequested() {
        phase = Phase.AwaitingStart
        requestedEntryId = null
        requestedPath = null
    }

    @Synchronized
    fun bindNativeIdentity(
        entryId: Long?,
        path: String,
    ) {
        requestedEntryId = entryId
        requestedPath = path
    }

    private fun matches(identity: MpvNativeFileIdentity?): Boolean {
        if (requestedPath == null) return true
        identity ?: return false
        val sameEntry = requestedEntryId == null || identity.entryId == requestedEntryId
        if (sameEntry && mpvMediaPathsMatch(requestedPath, identity.path)) return true
        // A libmpv playlist expansion creates newer children with an explicit parent playlist path.
        return requestedEntryId != null &&
            identity.entryId != null &&
            identity.entryId >= requestedEntryId!! &&
            mpvMediaPathsMatch(requestedPath, identity.playlistPath)
    }

    @Synchronized
    fun onStartFile(identity: MpvNativeFileIdentity? = null): Boolean {
        if (!matches(identity)) return false
        phase = Phase.Loading
        return true
    }

    @Synchronized
    fun onFileLoaded(
        identity: MpvNativeFileIdentity? = null,
        demuxerReady: Boolean = true,
    ): Boolean {
        if (phase != Phase.Loading || !matches(identity) || !demuxerReady) return false
        phase = Phase.Loaded
        return true
    }

    @Synchronized
    fun acceptsEndOfFile(identity: MpvNativeFileIdentity? = null): Boolean =
        matches(identity) && (phase == Phase.Loaded || phase == Phase.Started)

    @Synchronized
    fun awaitingInitialization(): Boolean = phase == Phase.Loading

    @Synchronized
    fun defersPlaylistExpansionEnd(next: MpvNativeFileIdentity?): Boolean =
        (phase == Phase.AwaitingStart || phase == Phase.Loading) &&
            requestedPath != null &&
            next != null &&
            next.entryId != null &&
            requestedEntryId != null &&
            next.entryId >= requestedEntryId!! &&
            mpvMediaPathsMatch(requestedPath, next.playlistPath)

    @Synchronized
    fun onPlaybackRestart() {
        if (phase == Phase.Loaded) phase = Phase.Started
    }

    @Synchronized
    fun canReportRendering(
        outputConfigured: Boolean,
        surfaceValid: Boolean,
    ): Boolean = phase == Phase.Started && outputConfigured && surfaceValid
}
