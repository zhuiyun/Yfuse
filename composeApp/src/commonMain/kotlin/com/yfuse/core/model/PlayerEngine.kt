package com.yfuse.core.model

/** The UI name is independent of the persisted SenPlayer and LockSenPlayer identifiers. */
internal const val SEN_PLAYER_DISPLAY_NAME = "SCore"

/** Selectable playback backends. */
enum class PlayerEngine(
    val label: String,
    val available: Boolean,
) {
    /** Yfuse-owned demux, decoder, renderer and transport orchestration. */
    YCore("YCore", true),

    /** Explicit opt-in only; original runtime license verification is required. */
    SenPlayer("$SEN_PLAYER_DISPLAY_NAME（实验）", true),
    ;

    companion object {
        val selectable: List<PlayerEngine>
            get() = entries.filter { it.available && it in bundledPlayerEngines() }
    }
}

/** Runtime/platform boundary; unavailable native ABIs must not be selectable. */
expect fun bundledPlayerEngines(): Set<PlayerEngine>
