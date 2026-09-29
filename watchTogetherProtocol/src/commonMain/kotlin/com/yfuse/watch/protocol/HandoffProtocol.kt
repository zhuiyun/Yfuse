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
    /** What the device is playing, sealed in the account vault like a transfer; null when idle. */
    val nowPlaying: HandoffEnvelope? = null,
    /** The device is asking another one to hand its playback over — 在此继续. */
    val pull: HandoffPull? = null,
    /**
     * 手机遥控: a television in the foreground hosting a remote session on the watch relay, which
     * a phone of the same account can join by [sessionId].
     */
    val acceptsRemote: Boolean = false,
    /**
     * 用手机登录: that television's 添加服务器 is waiting for a phone of the same account to hand it a
     * server over the relay ([WatchProtocol.CAPABILITY_REMOTE_SIGN_IN]). Only one that
     * [acceptsRemote] can: it asks on the socket it hosts on.
     */
    val asksRemoteSignIn: Boolean = false,
)

/**
 * 在此继续: a device asks [sourceSessionId] to send it what it is playing. The source answers with
 * an ordinary [HandoffOffer], so it still pauses only once the asking device is ready. [id] tells
 * one request from the next.
 */
@Serializable
data class HandoffPull(
    val sourceSessionId: String,
    val id: String,
)

/**
 * [nowPlaying], [pull], [acceptsRemote] and [asksRemoteSignIn] are optional and newer than the
 * rest: a service that predates them ignores them, and a device that predates them sends none.
 */
@Serializable
data class HandoffHeartbeat(
    val name: String,
    val platform: String,
    val canReceive: Boolean,
    val nowPlaying: HandoffEnvelope? = null,
    val pull: HandoffPull? = null,
    val acceptsRemote: Boolean = false,
    val asksRemoteSignIn: Boolean = false,
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
