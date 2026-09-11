package com.yfuse.core2.android

/** A released wrapper still owns its codecs until its worker finishes cleanup. */
internal interface AndroidSerializedPlayerRelease {
    val releaseCompleted: Boolean

    suspend fun releaseAndJoin()
}

/** A timeout reports a failure but does not grant permission to allocate the next decoder. */
internal class AndroidPlayerReleaseBarrier {
    private var pending: AndroidSerializedPlayerRelease? = null

    fun retire(player: AndroidSerializedPlayerRelease) {
        check(pending == null || pending === player || pending?.releaseCompleted == true) {
            "A previous decoder is still releasing"
        }
        pending = player
    }

    suspend fun await() {
        val previous = pending ?: return
        if (!previous.releaseCompleted) previous.releaseAndJoin()
        check(previous.releaseCompleted) { "Previous decoder cleanup is not complete" }
        pending = null
    }
}
