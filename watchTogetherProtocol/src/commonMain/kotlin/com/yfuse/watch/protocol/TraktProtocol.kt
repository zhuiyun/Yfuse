package com.yfuse.watch.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TraktConfiguration(
    val clientId: String = "",
    val oauthAvailable: Boolean = false,
    val deviceAvailable: Boolean = false,
)

@Serializable
data class TraktAuthStart(
    val device: Boolean = false,
)

@Serializable
data class TraktAuthChallenge(
    val id: String,
    val verificationUrl: String,
    val userCode: String? = null,
    val expiresAtEpochMs: Long,
    val intervalSeconds: Int = 5,
)

@Serializable
data class TraktToken(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("token_type") val tokenType: String = "bearer",
) {
    fun valid(): Boolean =
        accessToken.length in 1..4096 &&
            refreshToken.length in 1..4096 &&
            expiresIn in 1..31_536_000 &&
            createdAt in 1..100_000_000_000 &&
            tokenType.equals("bearer", true)
}

@Serializable
enum class TraktAuthStatus { Pending, Connected, Denied, Expired, Used }

@Serializable
data class TraktAuthPoll(
    val status: TraktAuthStatus,
    val token: TraktToken? = null,
    val retryAfterSeconds: Int = 5,
)

@Serializable
data class TraktRefreshRequest(
    val refreshToken: String,
    val requestId: String,
)

@Serializable
data class TraktRevokeRequest(
    val accessToken: String,
)
