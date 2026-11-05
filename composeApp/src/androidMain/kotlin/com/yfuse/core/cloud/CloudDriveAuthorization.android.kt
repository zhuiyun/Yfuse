package com.yfuse.core.cloud

import com.yfuse.core.filesource.FileSourceException
import com.yfuse.core.filesource.FileSourceFailure
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

actual fun createCloudDriveAuthorization(): CloudDriveAuthorization = AndroidCloudDriveAuthorization()

internal class AndroidCloudDriveAuthorization(
    private val http: OkHttpClient = cloudDriveHttpClient(),
    private val oauthOrigin: String = "https://openapi.baidu.com",
    private val now: () -> Long = System::currentTimeMillis,
) : CloudDriveAuthorization {
    private var slowDownMs = 0L

    override suspend fun beginBaiduAuthorization(credentials: CloudDriveCredentials): CloudDeviceAuthorization {
        require(credentials.clientId.isNotBlank()) { "请填写百度网盘应用 AppKey" }
        slowDownMs = 0L
        val result =
            http.cloudJson(
                Request
                    .Builder()
                    .url(
                        "$oauthOrigin/oauth/2.0/device/code"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("response_type", "device_code")
                            .addQueryParameter("client_id", credentials.clientId)
                            .addQueryParameter("scope", "basic,netdisk")
                            .build(),
                    ).build(),
                allowOAuthError = true,
            )
        if (result.text("device_code").isEmpty()) throw FileSourceException(FileSourceFailure.Unauthorized)
        val verificationUrl =
            try {
                result.text("verification_url").toHttpUrl().toString()
            } catch (
                _: IllegalArgumentException,
            ) {
                throw FileSourceException(FileSourceFailure.InvalidResponse)
            }
        return CloudDeviceAuthorization(
            result.text("device_code"),
            result.text("user_code"),
            verificationUrl,
            result.number("expires_in").toInt().coerceIn(1, 3600),
            result.number("interval").toInt().coerceIn(5, 60),
        )
    }

    override suspend fun pollBaiduAuthorization(
        credentials: CloudDriveCredentials,
        deviceCode: String,
    ): CloudDriveCredentials? {
        if (slowDownMs > 0) delay(slowDownMs)
        val result =
            http.cloudJson(
                Request
                    .Builder()
                    .url(
                        "$oauthOrigin/oauth/2.0/token"
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("grant_type", "device_token")
                            .addQueryParameter("code", deviceCode)
                            .addQueryParameter("client_id", credentials.clientId)
                            .addQueryParameter("client_secret", credentials.clientSecret)
                            .build(),
                    ).build(),
                allowOAuthError = true,
            )
        if (result.text("error") == "slow_down") {
            slowDownMs = (slowDownMs + 5000).coerceAtMost(60_000)
            return null
        }
        if (result.text("error") == "authorization_pending") return null
        if (result.text("access_token").isBlank()) throw FileSourceException(FileSourceFailure.Unauthorized)
        return credentials.copy(
            accessToken = result.text("access_token"),
            refreshToken = result.text("refresh_token"),
            expiresAtEpochMs = now() + result.number("expires_in").coerceIn(1, 31_536_000) * 1000,
        )
    }
}

internal fun cloudDriveHttpClient(): OkHttpClient =
    OkHttpClient
        .Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
