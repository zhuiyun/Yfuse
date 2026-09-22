package com.yfuse.watch.protocol

import kotlinx.serialization.Serializable

/**
 * A rejected CAS write whose previous entity is no longer retained by the relay.
 * Creation retries use baseCursor = 0 and still perform CAS, preserving concurrent recreations.
 */
@Serializable
data class PlaybackMissingEntity(
    val entityKey: String,
    val mutationId: String,
    val baseCursor: Long,
)
