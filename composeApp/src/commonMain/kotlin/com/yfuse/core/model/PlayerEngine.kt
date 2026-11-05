package com.yfuse.core.model

/** Selectable playback backends. */
enum class PlayerEngine(
    val label: String,
    val available: Boolean,
) {
    /** Media3/ExoPlayer — the default. */
    Exo("ExoPlayer", true),

    /** libmpv — widest format/codec coverage. */
    Mpv("MPV", true),

    /** MDK — hardware-accelerated libmdk renderer and decoder stack. */
    Mdk("MDK", true),
    /** User-selected experimental SenPlayer binary adapter; never an automatic fallback. */
    SenPlayer("SenPlayer（实验）", true),
    ;

    companion object {
        val selectable: List<PlayerEngine>
            get() = entries.filter { it.available && it in bundledPlayerEngines() }
    }
}

/** Package-profile boundary: compact Android packages deliberately omit MDK. */
expect fun bundledPlayerEngines(): Set<PlayerEngine>
