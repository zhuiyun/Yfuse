package com.yfuse.watch.protocol

import kotlinx.serialization.Serializable

/** Account-session identifiers are assigned by the server, never chosen by a device. */
@Serializable
data class HandoffDevice(
    val sessionId: String,
    val name: String,
    val platform: String,
    val lastSeenAtEpochMs: Long,
    val canReceive: Boolean,
)

@Serializable
data class HandoffHeartbeat(
    val name: String,
    val platform: String,
    val canReceive: Boolean,
)

/** The media identity, credentials-free locator and track preferences stay inside the vault. */
@Serializable
data class HandoffEnvelope(
    val nonce: String,
    val ciphertext: String,
)

@Serializable
enum class HandoffStatus {
    Requested,
    Preparing,
    Ready,
    Committed,
    Completed,
    Rejected,
    Failed,
    Cancelled,
    Expired,
    ;

    val terminal: Boolean
        get() = this in setOf(Completed, Rejected, Failed, Cancelled, Expired)
}

@Serializable
data class HandoffOffer(
    val id: String,
    val targetSessionId: String,
    val payload: HandoffEnvelope,
    val lifetimeSeconds: Int = 60,
)

@Serializable
data class HandoffRequest(
    val id: String,
    val sourceSessionId: String,
    val targetSessionId: String,
    val sourceName: String,
    val expiresAtEpochMs: Long,
    val payload: HandoffEnvelope,
    val status: HandoffStatus = HandoffStatus.Requested,
)

@Serializable
data class HandoffTransition(
    val status: HandoffStatus,
    val payload: HandoffEnvelope? = null,
)

@Serializable
data class HandoffInbox(
    val currentSessionId: String,
    val devices: List<HandoffDevice>,
    val requests: List<HandoffRequest>,
    val serverTimeEpochMs: Long,
)
