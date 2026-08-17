package com.yfuse.core.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.KeysExpiredException
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import androidx.media3.exoplayer.offline.DownloadHelper
import com.russhwolf.settings.Settings
import com.yfuse.core.security.createSecureStore
import com.yfuse.core.util.androidAppContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@UnstableApi
actual fun createPlaybackOfflineLicenseManager(settings: Settings): PlaybackOfflineLicenseManager {
    val context =
        androidAppContext
            ?: throw PlaybackOfflineLicenseException("Android application context is unavailable")
    return AndroidPlaybackOfflineLicenseManager(
        context = context,
        catalog =
            PlaybackOfflineLicenseCatalog(
                settings = settings,
                secureStore = createSecureStore(settings, namespace = "playback.drm-licenses"),
            ),
    )
}

@UnstableApi
private class AndroidPlaybackOfflineLicenseManager(
    private val context: Context,
    private val catalog: PlaybackOfflineLicenseCatalog,
) : PlaybackOfflineLicenseManager {
    private val operations = Mutex()

    override fun licenses(): List<PlaybackOfflineLicense> = catalog.list()

    override suspend fun acquire(request: PlaybackOfflineLicenseRequest): PlaybackOfflineLicense =
        operations.withLock {
            ensureWidevine(request.configuration)
            val format = resolveDrmFormat(request)
            withLicenseHelper(request.configuration) { helper ->
                val keySetId = helper.downloadLicense(format)
                try {
                    val now = System.currentTimeMillis()
                    val license =
                        helper
                            .getLicenseDurationRemainingSec(keySetId)
                            .toLicense(
                                id = UUID.randomUUID().toString(),
                                acquiredAtEpochMs = now,
                                updatedAtEpochMs = now,
                            )
                    catalog.put(license, keySetId)
                } catch (error: Throwable) {
                    runCatching { helper.releaseLicense(keySetId) }
                    throw wrap(error)
                } finally {
                    keySetId.fill(0)
                }
            }
        }

    override suspend fun status(licenseId: String): PlaybackOfflineLicense =
        operations.withLock {
            val stored = catalog.get(licenseId)
            ensureWidevine(stored.scheme)
            val keySetId = catalog.keySetId(licenseId)
            try {
                withLicenseHelper(null) { helper ->
                    val updated =
                        helper
                            .getLicenseDurationRemainingSec(keySetId)
                            .toLicense(
                                id = stored.id,
                                acquiredAtEpochMs = stored.acquiredAtEpochMs,
                                updatedAtEpochMs = System.currentTimeMillis(),
                            )
                    catalog.update(updated)
                    catalog.get(updated.id)
                }
            } catch (error: Throwable) {
                if (!error.hasCause<KeysExpiredException>()) throw wrap(error)
                val now = System.currentTimeMillis()
                val expired =
                    stored.copy(
                        updatedAtEpochMs = now,
                        licenseExpiresAtEpochMs = now,
                        playbackExpiresAtEpochMs = now,
                        state = PlaybackOfflineLicenseState.Expired,
                    )
                catalog.update(expired)
                expired
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
                withLicenseHelper(configuration) { helper ->
                    val renewedKeySetId = helper.renewLicense(oldKeySetId)
                    try {
                        val updated =
                            helper
                                .getLicenseDurationRemainingSec(renewedKeySetId)
                                .toLicense(
                                    id = stored.id,
                                    acquiredAtEpochMs = stored.acquiredAtEpochMs,
                                    updatedAtEpochMs = System.currentTimeMillis(),
                                )
                        catalog.put(updated, renewedKeySetId)
                    } finally {
                        renewedKeySetId.fill(0)
                    }
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
            withLicenseHelper(configuration) { helper -> helper.releaseLicense(keySetId) }
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

    private suspend fun resolveDrmFormat(request: PlaybackOfflineLicenseRequest): Format {
        request.drmInitializationData?.takeIf { it.isNotEmpty() }?.let { data ->
            return Format
                .Builder()
                .setSampleMimeType(request.drmMimeType)
                .setDrmInitData(
                    DrmInitData(
                        DrmInitData.SchemeData(C.WIDEVINE_UUID, request.drmMimeType, data.copyOf()),
                    ),
                ).build()
        }
        if (!request.mediaUri.isAdaptivePlaybackManifest()) {
            throw PlaybackOfflineLicenseException(
                "Progressive encrypted media requires DRM initialization data",
            )
        }
        return loadManifestDrmFormat(request)
    }

    private suspend fun loadManifestDrmFormat(request: PlaybackOfflineLicenseRequest): Format =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val httpFactory =
                    DefaultHttpDataSource.Factory().apply {
                        if (request.mediaRequestHeaders.isNotEmpty()) {
                            setDefaultRequestProperties(request.mediaRequestHeaders)
                        }
                    }
                val mediaItem = MediaItem.Builder().setUri(request.mediaUri).build()
                val helper =
                    DownloadHelper
                        .Factory()
                        .setDataSourceFactory(DefaultDataSource.Factory(context, httpFactory))
                        .setRenderersFactory(DefaultRenderersFactory(context))
                        .create(mediaItem)
                continuation.invokeOnCancellation { helper.release() }
                helper.prepare(
                    object : DownloadHelper.Callback {
                        override fun onPrepared(
                            prepared: DownloadHelper,
                            isLive: Boolean,
                        ) {
                            if (isLive) {
                                prepared.release()
                                if (!continuation.isActive) return
                                continuation.resumeWithException(
                                    PlaybackOfflineLicenseException(
                                        "Live DRM licenses are outside offline playback scope",
                                    ),
                                )
                                return
                            }
                            val format =
                                (0 until prepared.periodCount)
                                    .asSequence()
                                    .flatMap { period ->
                                        val groups = prepared.getTrackGroups(period)
                                        (0 until groups.length).asSequence().flatMap { groupIndex ->
                                            val group = groups[groupIndex]
                                            (0 until group.length).asSequence().map(group::getFormat)
                                        }
                                    }.firstOrNull { it.drmInitData != null }
                            prepared.release()
                            if (!continuation.isActive) return
                            if (format == null) {
                                continuation.resumeWithException(
                                    PlaybackOfflineLicenseException(
                                        "Adaptive manifest contains no DRM initialization data",
                                    ),
                                )
                            } else {
                                continuation.resume(format)
                            }
                        }

                        override fun onPrepareError(
                            failed: DownloadHelper,
                            error: IOException,
                        ) {
                            failed.release()
                            if (!continuation.isActive) return
                            continuation.resumeWithException(
                                PlaybackOfflineLicenseException(
                                    "Could not load DRM media manifest",
                                    error,
                                ),
                            )
                        }
                    },
                )
            }
        }

    private suspend fun <T> withLicenseHelper(
        configuration: PlaybackDrmConfiguration?,
        block: (OfflineLicenseHelper) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val licenseUri = configuration?.licenseUri.orEmpty()
            if (configuration != null && licenseUri.isBlank()) {
                throw PlaybackOfflineLicenseException("Widevine license URI is required")
            }
            val dataSourceFactory =
                DefaultHttpDataSource.Factory().apply {
                    configuration?.requestHeaders?.takeIf { it.isNotEmpty() }?.let {
                        setDefaultRequestProperties(it)
                    }
                }
            val helper =
                OfflineLicenseHelper.newWidevineInstance(
                    licenseUri,
                    configuration?.forceDefaultLicenseUri == true,
                    dataSourceFactory,
                    DrmSessionEventListener.EventDispatcher(),
                )
            try {
                block(helper)
            } finally {
                helper.release()
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

private fun android.util.Pair<Long, Long>.toLicense(
    id: String,
    acquiredAtEpochMs: Long,
    updatedAtEpochMs: Long,
): PlaybackOfflineLicense =
    PlaybackOfflineLicense(
        id = id,
        scheme = PlaybackDrmScheme.Widevine,
        acquiredAtEpochMs = acquiredAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
        licenseExpiresAtEpochMs = first.toExpiryEpochMs(updatedAtEpochMs),
        playbackExpiresAtEpochMs = second.toExpiryEpochMs(updatedAtEpochMs),
    )

private fun Long.toExpiryEpochMs(nowEpochMs: Long): Long? {
    if (this == C.TIME_UNSET) return null
    if (this <= 0L) return nowEpochMs
    val remainingMs = saturatingSecondsToMillis()
    return if (nowEpochMs > Long.MAX_VALUE - remainingMs) Long.MAX_VALUE else nowEpochMs + remainingMs
}

private fun Long.saturatingSecondsToMillis(): Long =
    if (this > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else this * 1_000L

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this as Throwable?) { it.cause }.any { it is T }
