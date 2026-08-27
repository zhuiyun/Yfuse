package com.yfuse.core2.android

import android.media.MediaCrypto
import android.media.MediaDrm
import android.media.NotProvisionedException
import android.os.Looper
import android.os.SystemClock
import com.yfuse.core.playback.PlaybackDrmConfiguration
import com.yfuse.core.playback.PlaybackDrmScheme
import com.yfuse.core2.drm.shouldRenewDrmKeys
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportMethod
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidYCoreDrmBinding internal constructor(
    val mediaCrypto: MediaCrypto,
    val requiresSecureVideoDecoder: Boolean,
)

/** Owns one Android MediaDrm session and its credential-bearing license exchange. */
internal class AndroidYCoreDrmSession(
    private val configuration: PlaybackDrmConfiguration,
    private val createMediaDrm: (UUID) -> MediaDrm = ::MediaDrm,
    private val createMediaCrypto: (UUID, ByteArray) -> MediaCrypto = ::MediaCrypto,
    private val createTransport: () -> YMediaTransport = ::AndroidHttpMediaTransport,
) : Closeable {
    private val schemeUuid = configuration.scheme.yCorePlatformUuid()
    private var mediaDrm: MediaDrm? = null
    private var sessionId: ByteArray? = null
    private var mediaCrypto: MediaCrypto? = null
    private var binding: AndroidYCoreDrmBinding? = null
    private var initializationData: ByteArray? = null
    private var videoMimeType: String? = null
    private var lastKeyStatusCheckMs = Long.MIN_VALUE
    private val keyRenewalRequired = AtomicBoolean(false)
    private val sessionReclaimed = AtomicBoolean(false)
    private val keyOutputRestricted = AtomicBoolean(false)

    @Synchronized
    fun open(
        initializationData: ByteArray,
        videoMimeType: String,
    ): AndroidYCoreDrmBinding {
        checkWorkerThreadForDrm()
        binding?.let { return it }
        require(initializationData.isNotEmpty() && initializationData.size <= MAX_DRM_INITIALIZATION_BYTES) {
            "DRM initialization data is empty or too large"
        }
        require(videoMimeType.startsWith("video/")) { "DRM video MIME type is invalid" }
        val drm = createMediaDrm(schemeUuid)
        mediaDrm = drm
        val eventListener =
            MediaDrm.OnEventListener { _, eventSessionId, event, _, _ ->
                val activeSession = sessionId
                if (activeSession != null && activeSession.contentEquals(eventSessionId)) {
                    when (event) {
                        MediaDrm.EVENT_KEY_REQUIRED,
                        MediaDrm.EVENT_KEY_EXPIRED,
                        -> keyRenewalRequired.set(true)
                        MediaDrm.EVENT_SESSION_RECLAIMED -> sessionReclaimed.set(true)
                    }
                }
            }
        try {
            drm.setOnEventListener(eventListener)
            drm.setOnKeyStatusChangeListener(
                MediaDrm.OnKeyStatusChangeListener { _, eventSessionId, keyInformation, _ ->
                    val activeSession = sessionId
                    if (activeSession != null && activeSession.contentEquals(eventSessionId)) {
                        if (keyInformation.any { it.statusCode == MediaDrm.KeyStatus.STATUS_EXPIRED }) {
                            keyRenewalRequired.set(true)
                        }
                        if (keyInformation.any { it.statusCode == MediaDrm.KeyStatus.STATUS_OUTPUT_NOT_ALLOWED }) {
                            keyOutputRestricted.set(true)
                        }
                    }
                },
                null,
            )
            val openedSession = withProvisioning(drm) { drm.openSession() }
            sessionId = openedSession
            val offlineKeySetId = configuration.offlineKeySetId
            if (offlineKeySetId != null) {
                require(offlineKeySetId.isNotEmpty() && offlineKeySetId.size <= MAX_OFFLINE_KEY_SET_BYTES) {
                    "Offline DRM key set is empty or too large"
                }
                withProvisioning(drm) { drm.restoreKeys(openedSession, offlineKeySetId) }
            } else {
                withProvisioning(drm) {
                    acquireStreamingKeys(
                        drm = drm,
                        openedSession = openedSession,
                        initializationData = initializationData,
                        videoMimeType = videoMimeType,
                    )
                }
            }
            val crypto = createMediaCrypto(schemeUuid, openedSession)
            mediaCrypto = crypto
            this.initializationData = initializationData.copyOf()
            this.videoMimeType = videoMimeType
            lastKeyStatusCheckMs = SystemClock.elapsedRealtime()
            keyRenewalRequired.set(false)
            sessionReclaimed.set(false)
            keyOutputRestricted.set(false)
            return AndroidYCoreDrmBinding(
                mediaCrypto = crypto,
                requiresSecureVideoDecoder = crypto.requiresSecureDecoderComponent(videoMimeType),
            ).also { binding = it }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    /** Renews streaming keys after MediaDrm events or before a reported license reaches expiry. */
    @Synchronized
    fun refreshKeysIfNeeded() {
        checkWorkerThreadForDrm()
        if (binding == null) return
        check(!sessionReclaimed.get()) { "DRM session was reclaimed by the platform" }
        check(!keyOutputRestricted.get()) { "DRM keys forbid the active output route" }
        if (configuration.offlineKeySetId != null) {
            check(!keyRenewalRequired.get()) { "Offline DRM keys are no longer usable" }
            return
        }
        val drm = checkNotNull(mediaDrm)
        val openedSession = checkNotNull(sessionId)
        val nowMs = SystemClock.elapsedRealtime()
        val eventRequested = keyRenewalRequired.getAndSet(false)
        if (!eventRequested && nowMs - lastKeyStatusCheckMs < KEY_STATUS_CHECK_INTERVAL_MS) return
        lastKeyStatusCheckMs = nowMs
        try {
            val status = drm.queryKeyStatus(openedSession)
            if (!shouldRenewDrmKeys(eventRequested, status)) return
            withProvisioning(drm) {
                acquireStreamingKeys(
                    drm = drm,
                    openedSession = openedSession,
                    initializationData = checkNotNull(initializationData),
                    videoMimeType = checkNotNull(videoMimeType),
                )
            }
        } catch (failure: Throwable) {
            if (eventRequested) keyRenewalRequired.set(true)
            throw failure
        }
    }

    private fun acquireStreamingKeys(
        drm: MediaDrm,
        openedSession: ByteArray,
        initializationData: ByteArray,
        videoMimeType: String,
    ) {
        val keyRequest =
            drm.getKeyRequest(
                openedSession,
                initializationData,
                CENC_INIT_DATA_MIME_TYPE,
                MediaDrm.KEY_TYPE_STREAMING,
                hashMapOf(),
            )
        require(keyRequest.data.isNotEmpty() && keyRequest.data.size <= MAX_LICENSE_CHALLENGE_BYTES) {
            "DRM license challenge is empty or too large"
        }
        // An explicit product URL wins even when a PSSH supplies a default. This prevents
        // credential-bearing request headers from being redirected to manifest-controlled hosts.
        val licenseUri =
            configuration.licenseUri?.takeIf(String::isNotBlank)
                ?: keyRequest.defaultUrl.takeIf(String::isNotBlank)
                ?: error("DRM license URI is unavailable")
        val response = postLicense(licenseUri, keyRequest.data)
        drm.provideKeyResponse(openedSession, response)
    }

    private fun postLicense(
        licenseUri: String,
        challenge: ByteArray,
    ): ByteArray {
        val requestHeaders =
            if (configuration.requestHeaders.keys.any { it.equals(CONTENT_TYPE_HEADER, ignoreCase = true) }) {
                configuration.requestHeaders
            } else {
                configuration.requestHeaders + (CONTENT_TYPE_HEADER to DRM_BINARY_CONTENT_TYPE)
            }
        return postDrmRequest(
            uri = licenseUri,
            challenge = challenge,
            headers = requestHeaders,
            maximumResponseBytes = MAX_LICENSE_RESPONSE_BYTES,
        )
    }

    private fun provision(drm: MediaDrm) {
        val request = drm.provisionRequest
        require(request.data.isNotEmpty() && request.data.size <= MAX_PROVISION_CHALLENGE_BYTES) {
            "DRM provisioning challenge is empty or too large"
        }
        val uri = request.defaultUrl.takeIf(String::isNotBlank) ?: error("DRM provisioning URI is unavailable")
        require(uri.licenseProtocol() == YSourceProtocol.Https) { "DRM provisioning requires HTTPS" }
        val response =
            postDrmRequest(
                uri = uri,
                challenge = request.data,
                headers = mapOf(CONTENT_TYPE_HEADER to DRM_BINARY_CONTENT_TYPE),
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

    private fun postDrmRequest(
        uri: String,
        challenge: ByteArray,
        headers: Map<String, String>,
        maximumResponseBytes: Int,
    ): ByteArray =
        runBlocking {
            val protocol = uri.licenseProtocol()
            val transport = createTransport()
            try {
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = uri,
                            protocol = protocol,
                            headers = headers,
                            method = YTransportMethod.Post,
                            body = challenge,
                        ),
                    )
                require(response.statusCode in 200..299) { "DRM server rejected the request" }
                response.contentLength?.let { length ->
                    require(length in 1..maximumResponseBytes.toLong()) {
                        "DRM response is empty or too large"
                    }
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(LICENSE_BUFFER_BYTES)
                while (true) {
                    val count = transport.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    require(output.size() <= maximumResponseBytes - count) {
                        "DRM response is too large"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().also { require(it.isNotEmpty()) { "DRM response is empty" } }
            } finally {
                transport.close()
            }
        }

    @Synchronized
    override fun close() {
        binding = null
        initializationData?.fill(0)
        initializationData = null
        videoMimeType = null
        lastKeyStatusCheckMs = Long.MIN_VALUE
        keyRenewalRequired.set(false)
        sessionReclaimed.set(false)
        keyOutputRestricted.set(false)
        mediaCrypto?.let { runCatching { it.release() } }
        mediaCrypto = null
        val drm = mediaDrm
        runCatching { drm?.setOnEventListener(null as MediaDrm.OnEventListener?) }
        runCatching {
            drm?.setOnKeyStatusChangeListener(null as MediaDrm.OnKeyStatusChangeListener?, null)
        }
        val openedSession = sessionId
        if (drm != null && openedSession != null) runCatching { drm.closeSession(openedSession) }
        sessionId = null
        drm?.let { runCatching { it.release() } }
        mediaDrm = null
    }
}

internal fun PlaybackDrmScheme.yCorePlatformUuid(): UUID =
    when (this) {
        PlaybackDrmScheme.Widevine -> UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
        PlaybackDrmScheme.ClearKey -> UUID.fromString("e2719d58-a985-b3c9-781a-b030af78d30e")
        PlaybackDrmScheme.PlayReady -> UUID.fromString("9a04f079-9840-4286-ab92-e65be0885f95")
    }

private fun String.licenseProtocol(): YSourceProtocol =
    when (runCatching { URI(this).scheme?.lowercase() }.getOrNull()) {
        "http" -> YSourceProtocol.Http
        "https" -> YSourceProtocol.Https
        else -> error("DRM license URI must use HTTP or HTTPS")
    }

private fun checkWorkerThreadForDrm() {
    check(Looper.myLooper() != Looper.getMainLooper()) { "DRM session work is forbidden on the main thread" }
}

private const val MAX_DRM_INITIALIZATION_BYTES = 8 * 1024 * 1024
private const val MAX_OFFLINE_KEY_SET_BYTES = 64 * 1024
private const val MAX_LICENSE_CHALLENGE_BYTES = 1024 * 1024
private const val MAX_LICENSE_RESPONSE_BYTES = 4 * 1024 * 1024
private const val MAX_PROVISION_CHALLENGE_BYTES = 1024 * 1024
private const val MAX_PROVISION_RESPONSE_BYTES = 4 * 1024 * 1024
private const val LICENSE_BUFFER_BYTES = 32 * 1024
private const val CENC_INIT_DATA_MIME_TYPE = "video/mp4"
private const val CONTENT_TYPE_HEADER = "Content-Type"
private const val DRM_BINARY_CONTENT_TYPE = "application/octet-stream"
private const val KEY_STATUS_CHECK_INTERVAL_MS = 30_000L
