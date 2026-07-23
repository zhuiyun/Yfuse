package com.yfuse.core.model

/** Selectable playback backends. */
enum class PlayerEngine(val label: String, val available: Boolean) {
    /** Media3/ExoPlayer — the default. */
    Exo("ExoPlayer", true),

    /** libmpv — widest format/codec coverage. */
    Mpv("MPV", true),

    /** MDK — ships native libs only; needs a JNI bridge before it can be used. */
    Mdk("MDK", false),
    ;

    companion object {
        val selectable: List<PlayerEngine> get() = entries.filter { it.available }
    }
}
