package com.yfuse.core2.android

import android.media.MediaCrypto
import android.media.MediaDrm
import android.os.Looper
import com.yfuse.core.playback.PlaybackDrmConfiguration
import com.yfuse.core.playback.PlaybackDrmScheme
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportMethod
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.URI
import java.util.UUID

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
    private val schemeUuid = configuration.scheme.platformUuid()
    private var mediaDrm: MediaDrm? = null
    private var sessionId: ByteArray? = null
    private var mediaCrypto: MediaCrypto? = null
    private var binding: AndroidYCoreDrmBinding? = null

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
        val openedSession = drm.openSession()
        sessionId = openedSession
        try {
            val offlineKeySetId = configuration.offlineKeySetId
            if (offlineKeySetId != null) {
                require(offlineKeySetId.isNotEmpty() && offlineKeySetId.size <= MAX_OFFLINE_KEY_SET_BYTES) {
                    "Offline DRM key set is empty or too large"
                }
                drm.restoreKeys(openedSession, offlineKeySetId)
            } else {
                acquireStreamingKeys(
                    drm = drm,
                    openedSession = openedSession,
                    initializationData = initializationData,
                    videoMimeType = videoMimeType,
                )
            }
            val crypto = createMediaCrypto(schemeUuid, openedSession)
            mediaCrypto = crypto
            return AndroidYCoreDrmBinding(
                mediaCrypto = crypto,
                requiresSecureVideoDecoder = crypto.requiresSecureDecoderComponent(videoMimeType),
            ).also { binding = it }
        } catch (failure: Throwable) {
            close()
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
                videoMimeType,
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
    ): ByteArray =
        runBlocking {
            val protocol = licenseUri.licenseProtocol()
            val transport = createTransport()
            try {
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = licenseUri,
                            protocol = protocol,
                            headers = configuration.requestHeaders,
                            method = YTransportMethod.Post,
                            body = challenge,
                        ),
                    )
                require(response.statusCode in 200..299) { "DRM license server rejected the request" }
                response.contentLength?.let { length ->
                    require(length in 1..MAX_LICENSE_RESPONSE_BYTES.toLong()) {
                        "DRM license response is empty or too large"
                    }
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(LICENSE_BUFFER_BYTES)
                while (true) {
                    val count = transport.read(buffer, 0, buffer.size)
                    if (count < 0) break
                    if (count == 0) continue
                    require(output.size() <= MAX_LICENSE_RESPONSE_BYTES - count) {
                        "DRM license response is too large"
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().also { require(it.isNotEmpty()) { "DRM license response is empty" } }
            } finally {
                transport.close()
            }
        }

    @Synchronized
    override fun close() {
        binding = null
        mediaCrypto?.let { runCatching { it.release() } }
        mediaCrypto = null
        val drm = mediaDrm
        val openedSession = sessionId
        if (drm != null && openedSession != null) runCatching { drm.closeSession(openedSession) }
        sessionId = null
        drm?.let { runCatching { it.release() } }
        mediaDrm = null
    }
}

private fun PlaybackDrmScheme.platformUuid(): UUID =
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
private const val LICENSE_BUFFER_BYTES = 32 * 1024
