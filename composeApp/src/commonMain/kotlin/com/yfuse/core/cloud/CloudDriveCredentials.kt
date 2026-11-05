package com.yfuse.core.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Encoded only into FileSourceRegistry's encrypted password slot, never normal settings. */
@Serializable
data class CloudDriveCredentials(
    val clientId: String = "",
    val clientSecret: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtEpochMs: Long = 0,
) {
    fun encode(): String = codec.encodeToString(serializer(), this)

    override fun toString(): String = "CloudDriveCredentials([redacted])"

    companion object {
        private val codec = Json { ignoreUnknownKeys = true }

        fun decode(value: String): CloudDriveCredentials = codec.decodeFromString(serializer(), value)
    }
}

data class CloudDeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
) {
    override fun toString(): String = "CloudDeviceAuthorization([redacted])"
}

interface CloudDriveAuthorization {
    suspend fun beginBaiduAuthorization(credentials: CloudDriveCredentials): CloudDeviceAuthorization

    /** Null means the user has not completed authorization; callers must honor the poll interval. */
    suspend fun pollBaiduAuthorization(
        credentials: CloudDriveCredentials,
        deviceCode: String,
    ): CloudDriveCredentials?
}

expect fun createCloudDriveAuthorization(): CloudDriveAuthorization

/** Removes rotated credentials when a source is forgotten, including process-restart copies. */
expect fun forgetCloudDriveAuthorization(sourceId: String)
