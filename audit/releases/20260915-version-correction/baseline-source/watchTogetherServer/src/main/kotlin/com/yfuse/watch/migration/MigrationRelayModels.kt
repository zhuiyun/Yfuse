package com.yfuse.watch.migration

import kotlinx.serialization.Serializable

@Serializable
internal data class CreateMigrationRelayRequest(
    val relayId: String,
    val transferSecret: String,
    val payloadSha256: String,
)

@Serializable
internal data class CreateMigrationRelayResponse(
    val code: String,
    val expiresAtEpochMs: Long,
)

@Serializable
internal data class RedeemMigrationRelayRequest(
    val relayId: String,
    val code: String,
    val payloadSha256: String,
)

@Serializable
internal data class RedeemMigrationRelayResponse(
    val transferSecret: String,
)

@Serializable
internal data class MigrationRelayErrorResponse(
    val code: String,
    val message: String,
)

internal class MigrationRelayException(
    val errorCode: String,
    override val message: String,
    val rateLimited: Boolean = false,
) : IllegalArgumentException(message)
