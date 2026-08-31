package com.yfuse.core.playback

import android.media.MediaDrm
import android.media.NotProvisionedException
import com.russhwolf.settings.Settings
import com.yfuse.core.security.createSecureStore
import com.yfuse.core2.adaptive.parseYDashManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

actual fun createPlaybackOfflineLicenseManager(settings: Settings): PlaybackOfflineLicenseManager =
    AndroidPlaybackOfflineLicenseManager(
        catalog =
            PlaybackOfflineLicenseCatalog(
                settings = settings,
                secureStore = createSecureStore(settings, namespace = "playback.drm-licenses"),
            ),
    )

/** Widevine offline ownership implemented directly on Android MediaDrm; no player runtime involved. */
private class AndroidPlaybackOfflineLicenseManager(
    private val catalog: PlaybackOfflineLicenseCatalog,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val createMediaDrm: (UUID) -> MediaDrm = ::MediaDrm,
) : PlaybackOfflineLicenseManager {
    private val operations = Mutex()

    override fun licenses(): List<PlaybackOfflineLicense> = catalog.list()

    override suspend fun acquire(request: PlaybackOfflineLicenseRequest): PlaybackOfflineLicense =
        operations.withLock {
            ensureWidevine(request.configuration)
            val initializationData = resolveInitializationData(request)
            try {
                withContext(Dispatchers.IO) {
                    withMediaDrm { drm ->
                        val sessionId = withProvisioning(drm) { drm.openSession() }
                        try {
                            val keyRequest =
                                withProvisioning(drm) {
                                    drm.getKeyRequest(
                                        sessionId,
                                        initializationData,
                                        request.drmMimeType,
                                        MediaDrm.KEY_TYPE_OFFLINE,
                                        hashMapOf(),
                                    )
                                }
                            val response = executeDrmRequest(request.configuration, keyRequest)
                            val keySetId =
                                withProvisioning(drm) { drm.provideKeyResponse(sessionId, response) }
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: throw PlaybackOfflineLicenseException(
                                        "Widevine did not return an offline key set",
                                    )
                            try {
                                val now = System.currentTimeMillis()
                                val license =
                                    drm.queryKeyStatus(sessionId).toOfflineLicense(
                                        id = UUID.randomUUID().toString(),
                                        acquiredAtEpochMs = now,
                                        updatedAtEpochMs = now,
                                    )
                                catalog.put(license, keySetId)
                            } catch (error: Throwable) {
                                runCatching { releaseKeySet(drm, keySetId, request.configuration) }
                                throw error
                            } finally {
                                keySetId.fill(0)
                            }
                        } finally {
                            runCatching { drm.closeSession(sessionId) }
                        }
                    }
                }
            } catch (error: Throwable) {
                throw wrap(error)
            } finally {
                initializationData.fill(0)
            }
        }

    override suspend fun status(licenseId: String): PlaybackOfflineLicense =
        operations.withLock {
            val stored = catalog.get(licenseId)
            ensureWidevine(stored.scheme)
            val keySetId = catalog.keySetId(licenseId)
            try {
                val updated =
                    withContext(Dispatchers.IO) {
                        withMediaDrm { drm ->
                            withRestoredSession(drm, keySetId) { sessionId ->
                                drm.queryKeyStatus(sessionId).toOfflineLicense(
                                    id = stored.id,
                                    acquiredAtEpochMs = stored.acquiredAtEpochMs,
                                    updatedAtEpochMs = System.currentTimeMillis(),
                                )
                            }
                        }
                    }
                catalog.update(updated)
                catalog.get(updated.id)
            } catch (error: Throwable) {
                throw wrap(error)
            } finally {
                keySetId.fill(0)
            }
        }

    override suspend fun renew(
        licenseId: String,
        configuration: PlaybackDrmConfiguration,
    ): PlaybackOfflineLicense =
        operations.withLock {
            ensureWidevine(configuration)
            val stored = catalog.get(licenseId)
            val oldKeySetId = catalog.keySetId(licenseId)
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        withMediaDrm { drm ->
                            val keyRequest =
                                withProvisioning(drm) {
                                    drm.getKeyRequest(
                                        oldKeySetId,
                                        null,
                                        null,
                                        MediaDrm.KEY_TYPE_OFFLINE,
                                        hashMapOf(),
                                    )
                                }
                            val response = executeDrmRequest(configuration, keyRequest)
                            val renewed =
                                withProvisioning(drm) { drm.provideKeyResponse(oldKeySetId, response) }
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: oldKeySetId.copyOf()
                            try {
                                val updated =
                                    withRestoredSession(drm, renewed) { sessionId ->
                                        drm.queryKeyStatus(sessionId).toOfflineLicense(
                                            id = stored.id,
                                            acquiredAtEpochMs = stored.acquiredAtEpochMs,
                                            updatedAtEpochMs = System.currentTimeMillis(),
                                        )
                                    }
                                updated to renewed.copyOf()
                            } finally {
                                renewed.fill(0)
                            }
                        }
                    }
                try {
                    catalog.put(result.first, result.second)
                } finally {
                    result.second.fill(0)
                }
            } catch (error: Throwable) {
                throw wrap(error)
            } finally {
                oldKeySetId.fill(0)
            }
        }

    override suspend fun renewIfNeeded(
        licenseId: String,
        configuration: PlaybackDrmConfiguration,
        thresholdSeconds: Long,
    ): PlaybackOfflineLicense {
        require(thresholdSeconds >= 0L) { "Renewal threshold cannot be negative" }
        val current = status(licenseId)
        val now = System.currentTimeMillis()
        val thresholdMs = thresholdSeconds.saturatingSecondsToMillis()
        val thresholdAt = if (now > Long.MAX_VALUE - thresholdMs) Long.MAX_VALUE else now + thresholdMs
        val earliestExpiry =
            listOfNotNull(current.licenseExpiresAtEpochMs, current.playbackExpiresAtEpochMs).minOrNull()
        return if (earliestExpiry != null && earliestExpiry <= thresholdAt) {
            renew(licenseId, configuration)
        } else {
            current
        }
    }

    override suspend fun release(
        licenseId: String,
        configuration: PlaybackDrmConfiguration,
    ) = operations.withLock {
        ensureWidevine(configuration)
        val keySetId = catalog.keySetId(licenseId)
        try {
            withContext(Dispatchers.IO) {
                withMediaDrm { drm -> releaseKeySet(drm, keySetId, configuration) }
            }
            catalog.remove(licenseId)
        } catch (error: Throwable) {
            throw wrap(error)
        } finally {
            keySetId.fill(0)
        }
    }

    override suspend fun forget(licenseId: String) = operations.withLock { catalog.remove(licenseId) }

    override fun configurationWithLicense(
        licenseId: String,
        configuration: PlaybackDrmConfiguration,
    ): PlaybackDrmConfiguration {
        ensureWidevine(configuration)
        return configuration.copy(offlineKeySetId = catalog.keySetId(licenseId))
    }

    private suspend fun resolveInitializationData(request: PlaybackOfflineLicenseRequest): ByteArray {
        request.drmInitializationData?.takeIf(ByteArray::isNotEmpty)?.let { return it.copyOf() }
        if (!request.mediaUri.isAdaptivePlaybackManifest()) {
            throw PlaybackOfflineLicenseException(
                "Progressive encrypted media requires DRM initialization data",
            )
        }
        val manifest =
            withContext(Dispatchers.IO) {
                executeHttp(
                    uri = request.mediaUri,
                    headers = request.mediaRequestHeaders,
                    body = null,
                    maximumResponseBytes = MAX_MANIFEST_BYTES,
                ).toString(StandardCharsets.UTF_8)
            }
        return when {
            request.mediaUri.substringBefore('?').endsWith(".mpd", ignoreCase = true) ->
                extractWidevinePsshFromDash(manifest, request.mediaUri)
            request.mediaUri.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ->
                extractWidevinePsshFromHls(manifest)
            else -> null
        } ?: throw PlaybackOfflineLicenseException(
            "Adaptive manifest contains no executable Widevine initialization data",
        )
    }

    private fun executeDrmRequest(
        configuration: PlaybackDrmConfiguration,
        keyRequest: MediaDrm.KeyRequest,
    ): ByteArray {
        val uri =
            configuration.licenseUri?.takeIf(String::isNotBlank)
                ?: keyRequest.defaultUrl.takeIf(String::isNotBlank)
                ?: throw PlaybackOfflineLicenseException("Widevine license URI is required")
        return executeHttp(
            uri = uri,
            headers = configuration.requestHeaders,
            body = keyRequest.data,
            maximumResponseBytes = MAX_LICENSE_RESPONSE_BYTES,
        )
    }

    private fun releaseKeySet(
        drm: MediaDrm,
        keySetId: ByteArray,
        configuration: PlaybackDrmConfiguration,
    ) {
        val keyRequest =
            withProvisioning(drm) {
                drm.getKeyRequest(
                    keySetId,
                    null,
                    null,
                    MediaDrm.KEY_TYPE_RELEASE,
                    hashMapOf(),
                )
            }
        val response = executeDrmRequest(configuration, keyRequest)
        withProvisioning(drm) { drm.provideKeyResponse(keySetId, response) }
    }

    private fun provision(drm: MediaDrm) {
        val request = drm.provisionRequest
        val separator = if ('?' in request.defaultUrl) '&' else '?'
        val signedRequest =
            URLEncoder.encode(
                Base64.getEncoder().encodeToString(request.data),
                StandardCharsets.UTF_8.name(),
            )
        val uri = "${request.defaultUrl}$separator" + "signedRequest=$signedRequest"
        val response =
            executeHttp(
                uri = uri,
                headers = emptyMap(),
                body = ByteArray(0),
                maximumResponseBytes = MAX_PROVISION_RESPONSE_BYTES,
            )
        drm.provideProvisionResponse(response)
    }

    private fun <T> withProvisioning(
        drm: MediaDrm,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (_: NotProvisionedException) {
            provision(drm)
            block()
        }

    private fun <T> withMediaDrm(block: (MediaDrm) -> T): T {
        val drm = createMediaDrm(WIDEVINE_UUID)
        return try {
            block(drm)
        } finally {
            runCatching { drm.release() }
        }
    }

    private fun <T> withRestoredSession(
        drm: MediaDrm,
        keySetId: ByteArray,
        block: (ByteArray) -> T,
    ): T {
        val sessionId = withProvisioning(drm) { drm.openSession() }
        return try {
            withProvisioning(drm) { drm.restoreKeys(sessionId, keySetId) }
            block(sessionId)
        } finally {
            runCatching { drm.closeSession(sessionId) }
        }
    }

    private fun executeHttp(
        uri: String,
        headers: Map<String, String>,
        body: ByteArray?,
        maximumResponseBytes: Int,
    ): ByteArray {
        requireHttpUri(uri)
        val builder = Request.Builder().url(uri)
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (body == null) {
            builder.get()
        } else {
            if (headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                builder.header("Content-Type", DRM_CONTENT_TYPE)
            }
            builder.post(body.toRequestBody(DRM_CONTENT_TYPE.toMediaType()))
        }
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw PlaybackOfflineLicenseException("DRM server rejected the request (${response.code})")
            }
            val responseBody =
                response.body ?: throw PlaybackOfflineLicenseException("DRM response is empty")
            responseBody.contentLength().takeIf { it >= 0L }?.let { length ->
                require(length <= maximumResponseBytes.toLong()) { "DRM response is too large" }
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(IO_BUFFER_BYTES)
            responseBody.byteStream().use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() <= maximumResponseBytes - count) { "DRM response is too large" }
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray().also { require(it.isNotEmpty()) { "DRM response is empty" } }
        }
    }

    private fun ensureWidevine(configuration: PlaybackDrmConfiguration) = ensureWidevine(configuration.scheme)

    private fun ensureWidevine(scheme: PlaybackDrmScheme) {
        if (scheme != PlaybackDrmScheme.Widevine) {
            throw PlaybackOfflineLicenseException("Offline licenses currently require Widevine")
        }
    }

    private fun wrap(error: Throwable): PlaybackOfflineLicenseException =
        error as? PlaybackOfflineLicenseException
            ?: PlaybackOfflineLicenseException("Offline DRM license operation failed", error)
}

internal fun extractWidevinePsshFromDash(
    manifest: String,
    baseUri: String,
): ByteArray? =
    parseYDashManifest(manifest, baseUri)
        .representations
        .asSequence()
        .flatMap { it.contentProtections.asSequence() }
        .firstOrNull { protection ->
            protection.schemeIdUri.contains(WIDEVINE_UUID.toString(), ignoreCase = true) ||
                protection.schemeIdUri.contains("widevine", ignoreCase = true)
        }?.psshBase64
        ?.let(::decodeBase64OrNull)

internal fun extractWidevinePsshFromHls(manifest: String): ByteArray? =
    manifest
        .lineSequence()
        .map(String::trim)
        .filter { line ->
            line.startsWith("#EXT-X-SESSION-KEY:", ignoreCase = true) ||
                line.startsWith("#EXT-X-KEY:", ignoreCase = true)
        }.mapNotNull { line ->
            val keyFormat = HLS_KEY_FORMAT.find(line)?.groupValues?.get(1).orEmpty()
            if (
                !keyFormat.contains(WIDEVINE_UUID.toString(), ignoreCase = true) &&
                !keyFormat.contains("widevine", ignoreCase = true)
            ) {
                return@mapNotNull null
            }
            val uri = HLS_KEY_URI.find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val encoded = uri.substringAfter("base64,", missingDelimiterValue = "")
            encoded.takeIf(String::isNotBlank)?.let(::decodeBase64OrNull)
        }.firstOrNull()

internal fun Map<String, String>.toOfflineLicense(
    id: String,
    acquiredAtEpochMs: Long,
    updatedAtEpochMs: Long,
): PlaybackOfflineLicense {
    val licenseSeconds = get("LicenseDurationRemaining").toRemainingSeconds()
    val playbackSeconds = get("PlaybackDurationRemaining").toRemainingSeconds()
    return PlaybackOfflineLicense(
        id = id,
        scheme = PlaybackDrmScheme.Widevine,
        acquiredAtEpochMs = acquiredAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        licenseExpiresAtEpochMs = licenseSeconds.toExpiryEpochMs(updatedAtEpochMs),
        playbackExpiresAtEpochMs = playbackSeconds.toExpiryEpochMs(updatedAtEpochMs),
    )
}

private fun String?.toRemainingSeconds(): Long? =
    this?.trim()?.toLongOrNull()?.takeIf { it >= 0L }

private fun Long?.toExpiryEpochMs(nowEpochMs: Long): Long? {
    val seconds = this ?: return null
    if (seconds == 0L) return nowEpochMs
    val remainingMs = seconds.saturatingSecondsToMillis()
    return if (nowEpochMs > Long.MAX_VALUE - remainingMs) Long.MAX_VALUE else nowEpochMs + remainingMs
}

private fun Long.saturatingSecondsToMillis(): Long =
    if (this > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else this * 1_000L

private fun decodeBase64OrNull(value: String): ByteArray? =
    runCatching { Base64.getDecoder().decode(value.trim()) }
        .getOrNull()
        ?.takeIf(ByteArray::isNotEmpty)

private fun requireHttpUri(value: String) {
    val scheme = runCatching { URI(value).scheme?.lowercase() }.getOrNull()
    require(scheme == "http" || scheme == "https") { "DRM request URI must use HTTP or HTTPS" }
}

private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
private const val MAX_LICENSE_RESPONSE_BYTES = 4 * 1024 * 1024
private const val MAX_PROVISION_RESPONSE_BYTES = 4 * 1024 * 1024
private const val IO_BUFFER_BYTES = 32 * 1024
private const val DRM_CONTENT_TYPE = "application/octet-stream"
private val WIDEVINE_UUID = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
private val HLS_KEY_FORMAT = Regex("KEYFORMAT=\\\"([^\\\"]+)\\\"", RegexOption.IGNORE_CASE)
private val HLS_KEY_URI = Regex("URI=\\\"([^\\\"]+)\\\"", RegexOption.IGNORE_CASE)
