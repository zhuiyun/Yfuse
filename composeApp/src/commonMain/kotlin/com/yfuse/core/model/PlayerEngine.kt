package com.yfuse.core.model

/** Selectable playback backends. */
enum class PlayerEngine(val label: String, val available: Boolean) {
    /** Media3/ExoPlayer — the default. */
    Exo("ExoPlayer", true),

    /** libmpv — widest format/codec coverage. */
    Mpv("MPV", true),

    /** MDK — hardware-accelerated libmdk renderer and decoder stack. */
    Mdk("MDK", true),
    ;

    companion object {
        val selectable: List<PlayerEngine> get() = entries.filter { it.available }
    }
}
