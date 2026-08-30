package com.yfuse.tv.integration

import android.content.Context
import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaError.DetailedErrorCode
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.CastReceiverOptions
import com.google.android.gms.cast.tv.ReceiverOptionsProvider
import com.google.android.gms.cast.tv.media.MediaException
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.yfuse.BuildConfig
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import java.net.URI

const val YFUSE_CAST_RECEIVER_APPLICATION_ID = "E9107559"
const val CAST_CONNECT_LAUNCH_ACTION = "com.google.android.gms.cast.tv.action.LAUNCH"
const val CAST_CONNECT_LOAD_ACTION = "com.google.android.gms.cast.tv.action.LOAD"

/** External setup cannot be inferred from a receiver id embedded in the APK. */
enum class CastDeveloperConsoleAssociation {
    Unverified,
    Verified,
}

enum class CastConnectIntentKind {
    Launch,
    Load,
    YfusePlaybackDeepLink,
    Other,
}

/** Pure classifier kept outside the SDK bridge so manifest/intents can be covered by unit tests. */
internal fun classifyCastConnectIntent(
    action: String?,
    data: String?,
): CastConnectIntentKind =
    when {
        action == CAST_CONNECT_LAUNCH_ACTION -> CastConnectIntentKind.Launch
        action == CAST_CONNECT_LOAD_ACTION -> CastConnectIntentKind.Load
        data != null && TvPlaybackDeepLinkCodec.decode(data) != null ->
            CastConnectIntentKind.YfusePlaybackDeepLink
        else -> CastConnectIntentKind.Other
    }

internal data class CastConnectLoadEnvelope(
    val senderId: String?,
    val entity: String?,
    val contentId: String?,
    val contentUrl: String?,
    val contentType: String?,
    val title: String?,
    val autoplay: Boolean,
    val positionMs: Long,
    /** The credential itself is intentionally neither copied nor logged. */
    val credentialsSupplied: Boolean,
)

sealed interface CastConnectPlaybackSource {
    /** Stable app identity. Resolution to a local authenticated server stays inside the TV app. */
    data class YfuseDeepLink(
        val uri: String,
        val route: EncodedTvPlaybackRoute,
    ) : CastConnectPlaybackSource

    /** Transient sender URL. It must never be persisted or written to diagnostic output. */
    class DirectMedia(
        val url: String,
    ) : CastConnectPlaybackSource {
        override fun equals(other: Any?): Boolean = other is DirectMedia && url == other.url

        override fun hashCode(): Int = url.hashCode()

        override fun toString(): String = "DirectMedia(url=<redacted>)"
    }
}

data class CastConnectPlaybackRequest(
    val senderId: String?,
    val source: CastConnectPlaybackSource,
    val contentType: String?,
    val title: String?,
    val autoplay: Boolean,
    val positionMs: Long,
    /** Only a presence bit is exposed; Yfuse always uses its TV-local authenticated profile. */
    val credentialsSupplied: Boolean,
)

fun interface CastConnectLoadHandler {
    /** Return true once the request has been validated and queued for playback by the host. */
    fun onLoad(request: CastConnectPlaybackRequest): Boolean
}

internal object CastConnectLoadMapper {
    fun map(envelope: CastConnectLoadEnvelope): CastConnectPlaybackRequest? {
        val position = normalizePosition(envelope.positionMs) ?: return null
        val deepLink =
            sequenceOf(envelope.entity, envelope.contentId, envelope.contentUrl)
                .filterNotNull()
                .mapNotNull { value ->
                    TvPlaybackDeepLinkCodec.decode(value)?.let { route -> value to route }
                }.firstOrNull()
        val source =
            if (deepLink != null) {
                val (uri, route) = deepLink
                CastConnectPlaybackSource.YfuseDeepLink(uri = uri, route = route)
            } else {
                val direct =
                    sequenceOf(envelope.contentUrl, envelope.contentId)
                        .filterNotNull()
                        .firstOrNull(::isSupportedCastConnectDirectMediaUrl)
                        ?: return null
                CastConnectPlaybackSource.DirectMedia(direct)
            }
        val effectivePosition =
            if (source is CastConnectPlaybackSource.YfuseDeepLink && position == 0L) {
                source.route.positionMs
            } else {
                position
            }
        return CastConnectPlaybackRequest(
            senderId = envelope.senderId?.take(MAX_SENDER_ID_CHARS),
            source = source,
            contentType = envelope.contentType?.trim()?.take(MAX_CONTENT_TYPE_CHARS)?.takeIf(String::isNotBlank),
            title = envelope.title?.trim()?.take(MAX_TITLE_CHARS)?.takeIf(String::isNotBlank),
            autoplay = envelope.autoplay,
            positionMs = effectivePosition,
            credentialsSupplied = envelope.credentialsSupplied,
        )
    }

    private fun normalizePosition(positionMs: Long): Long? =
        when {
            positionMs == MediaLoadRequestData.PLAY_POSITION_UNASSIGNED -> 0L
            positionMs < 0L -> null
            else -> positionMs
        }

    private const val MAX_SENDER_ID_CHARS = 256
    private const val MAX_CONTENT_TYPE_CHARS = 200
    private const val MAX_TITLE_CHARS = 500
}

internal fun isSupportedCastConnectDirectMediaUrl(value: String): Boolean {
    if (value.length !in 1..MAX_CAST_CONNECT_MEDIA_URL_CHARS) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https") &&
        !uri.host.isNullOrBlank() &&
        uri.userInfo == null &&
        uri.rawFragment == null
}

enum class CastConnectActionPersistence {
    /** The action may be consumed by the process-local launch registry, never disk/Intent extras. */
    TransientOnly,
}

sealed interface CastConnectHostAction {
    data class ResolveLibraryPlayback(
        val target: ResolvedTvPlaybackTarget,
        val title: String?,
        val autoplay: Boolean,
    ) : CastConnectHostAction

    /**
     * Direct sender media remains memory-only. [url] may contain an expiring query credential, so
     * this class redacts its string form and the host must only put it in PlayerLaunchRegistry.
     */
    class PlayDirect internal constructor(
        val url: String,
        val title: String?,
        val contentType: String?,
        val positionMs: Long,
        val autoplay: Boolean,
    ) : CastConnectHostAction {
        val transcodeAllowed: Boolean = false
        val persistence: CastConnectActionPersistence = CastConnectActionPersistence.TransientOnly

        override fun equals(other: Any?): Boolean =
            other is PlayDirect &&
                url == other.url &&
                title == other.title &&
                contentType == other.contentType &&
                positionMs == other.positionMs &&
                autoplay == other.autoplay

        override fun hashCode(): Int {
            var result = url.hashCode()
            result = 31 * result + (title?.hashCode() ?: 0)
            result = 31 * result + (contentType?.hashCode() ?: 0)
            result = 31 * result + positionMs.hashCode()
            result = 31 * result + autoplay.hashCode()
            return result
        }

        override fun toString(): String =
            "PlayDirect(url=<redacted>, title=$title, contentType=$contentType, " +
                "positionMs=$positionMs, autoplay=$autoplay)"
    }
}

/** Pure host mapping suitable for installation in TvMainActivity before the player is created. */
class CastConnectHostActionResolver(
    servers: () -> Collection<SavedServer>,
) {
    private val deepLinkResolver = TvPlaybackDeepLinkResolver(servers)

    fun resolve(request: CastConnectPlaybackRequest): CastConnectHostAction? {
        return when (val source = request.source) {
            is CastConnectPlaybackSource.YfuseDeepLink -> {
                val decoded = TvPlaybackDeepLinkCodec.decode(source.uri) ?: return null
                if (decoded != source.route) return null
                val target = deepLinkResolver.resolve(decoded) ?: return null
                CastConnectHostAction.ResolveLibraryPlayback(
                    target = target.copy(positionMs = request.positionMs),
                    title = request.title,
                    autoplay = request.autoplay,
                )
            }

            is CastConnectPlaybackSource.DirectMedia -> {
                if (!isSupportedCastConnectDirectMediaUrl(source.url)) return null
                CastConnectHostAction.PlayDirect(
                    url = source.url,
                    title = request.title,
                    contentType = request.contentType,
                    positionMs = request.positionMs.coerceAtLeast(0L),
                    autoplay = request.autoplay,
                )
            }
        }
    }
}

data class CastConnectReceiverStatus(
    val sdkInitialized: Boolean,
    val started: Boolean,
    val configuredReceiverApplicationId: String,
    val receiverApplicationIdMatches: Boolean,
    val developerConsoleAssociation: CastDeveloperConsoleAssociation,
    val loadHandlerInstalled: Boolean,
    val mediaSessionAttached: Boolean,
    val lastFailure: String? = null,
) {
    /** SDK-side wiring only; it is deliberately weaker than an end-to-end deployment claim. */
    val locallyReady: Boolean
        get() = sdkInitialized && started && loadHandlerInstalled && mediaSessionAttached && lastFailure == null

    /** True only when code readiness and the separately verified Cast Console association agree. */
    val endToEndDeploymentVerified: Boolean
        get() =
            locallyReady &&
                receiverApplicationIdMatches &&
                developerConsoleAssociation == CastDeveloperConsoleAssociation.Verified
}

sealed interface CastConnectIntentResult {
    data class Handled(
        val kind: CastConnectIntentKind,
    ) : CastConnectIntentResult

    data class NotHandled(
        val kind: CastConnectIntentKind,
    ) : CastConnectIntentResult

    data object NotInitialized : CastConnectIntentResult

    data class Failed(
        val kind: CastConnectIntentKind,
        val reason: String,
    ) : CastConnectIntentResult
}

/**
 * Direct Cast Connect receiver boundary.
 *
 * Application owns [initialize], [start] and [stop]. The TV launcher installs the load handler
 * before forwarding its initial intent through [onNewIntent]. PlayerActivity only attaches its
 * existing MediaSessionCompat token and forwards replacement playback intents. No second media
 * session is created here.
 */
object CastConnectReceiverBridge {
    private val lock = Any()

    @Volatile
    private var receiverContext: CastReceiverContext? = null

    @Volatile
    private var loadHandler: CastConnectLoadHandler? = null

    @Volatile
    private var started: Boolean = false

    @Volatile
    private var mediaSessionAttached: Boolean = false

    @Volatile
    private var developerConsoleAssociation = CastDeveloperConsoleAssociation.Unverified

    @Volatile
    private var lastFailure: String? = null

    /**
     * Initializes the official SDK and its LOAD callback. This does not prove Developer Console
     * package association; callers must only pass [CastDeveloperConsoleAssociation.Verified] from
     * a release configuration that has been checked outside the APK.
     */
    fun initialize(
        context: Context,
        consoleAssociation: CastDeveloperConsoleAssociation = CastDeveloperConsoleAssociation.Unverified,
    ): CastConnectReceiverStatus =
        synchronized(lock) {
            developerConsoleAssociation = consoleAssociation
            if (receiverContext == null) {
                runCatching {
                    CastReceiverContext.initInstance(context.applicationContext)
                    checkNotNull(CastReceiverContext.getInstance())
                }.onSuccess { initialized ->
                    receiverContext = initialized
                    initialized.mediaManager.setMediaLoadCommandCallback(ReceiverLoadCallback())
                    lastFailure = null
                }.onFailure { error ->
                    lastFailure = "cast_receiver_initialization_failed"
                    AppLog.warning(
                        category = "cast.connect",
                        event = "receiver_initialization_failed",
                        message = "Cast Connect receiver SDK could not be initialized",
                        throwable = error,
                    )
                }
            }
            status()
        }

    fun start(): CastConnectReceiverStatus =
        synchronized(lock) {
            val current = receiverContext
            if (current == null) {
                lastFailure = "cast_receiver_not_initialized"
            } else if (!started) {
                runCatching { current.start() }
                    .onSuccess {
                        started = true
                        lastFailure = null
                    }.onFailure { error ->
                        lastFailure = "cast_receiver_start_failed"
                        AppLog.warning(
                            category = "cast.connect",
                            event = "receiver_start_failed",
                            message = "Cast Connect receiver SDK could not start",
                            throwable = error,
                        )
                    }
            }
            status()
        }

    fun stop(): CastConnectReceiverStatus =
        synchronized(lock) {
            val current = receiverContext
            if (current != null && started) {
                runCatching { current.stop() }
                    .onSuccess {
                        started = false
                        lastFailure = null
                    }.onFailure { error ->
                        lastFailure = "cast_receiver_stop_failed"
                        AppLog.warning(
                            category = "cast.connect",
                            event = "receiver_stop_failed",
                            message = "Cast Connect receiver SDK could not stop",
                            throwable = error,
                        )
                    }
            }
            status()
        }

    fun installLoadHandler(handler: CastConnectLoadHandler) {
        loadHandler = handler
    }

    fun clearLoadHandler(handler: CastConnectLoadHandler? = null) {
        if (handler == null || loadHandler === handler) loadHandler = null
    }

    fun attachMediaSessionToken(token: MediaSessionCompat.Token): CastConnectReceiverStatus =
        synchronized(lock) {
            val current = receiverContext
            if (current == null) {
                lastFailure = "cast_receiver_not_initialized"
            } else {
                runCatching { current.mediaManager.setSessionCompatToken(token) }
                    .onSuccess {
                        mediaSessionAttached = true
                        lastFailure = null
                    }.onFailure { error ->
                        mediaSessionAttached = false
                        lastFailure = "cast_media_session_attach_failed"
                        AppLog.warning(
                            category = "cast.connect",
                            event = "media_session_attach_failed",
                            message = "Cast Connect could not attach the existing media session",
                            throwable = error,
                        )
                    }
            }
            status()
        }

    fun detachMediaSessionToken(): CastConnectReceiverStatus =
        synchronized(lock) {
            receiverContext?.let { current ->
                runCatching { current.mediaManager.setSessionCompatToken(null) }
                    .onSuccess {
                        mediaSessionAttached = false
                        lastFailure = null
                    }.onFailure { error ->
                        lastFailure = "cast_media_session_detach_failed"
                        AppLog.warning(
                            category = "cast.connect",
                            event = "media_session_detach_failed",
                            message = "Cast Connect could not detach the media session",
                            throwable = error,
                        )
                    }
            }
            status()
        }

    fun onNewIntent(intent: Intent): CastConnectIntentResult {
        val kind = classifyCastConnectIntent(intent.action, intent.dataString)
        val current = receiverContext ?: return CastConnectIntentResult.NotInitialized
        return runCatching { current.mediaManager.onNewIntent(intent) }
            .fold(
                onSuccess = { handled ->
                    if (handled) {
                        CastConnectIntentResult.Handled(kind)
                    } else {
                        CastConnectIntentResult.NotHandled(kind)
                    }
                },
                onFailure = { error ->
                    lastFailure = "cast_intent_dispatch_failed"
                    AppLog.warning(
                        category = "cast.connect",
                        event = "intent_dispatch_failed",
                        message = "Cast Connect rejected an activity intent",
                        throwable = error,
                    )
                    CastConnectIntentResult.Failed(kind, "cast_intent_dispatch_failed")
                },
            )
    }

    fun status(): CastConnectReceiverStatus {
        val configuredId = BuildConfig.YFUSE_CAST_RECEIVER_APPLICATION_ID.trim().uppercase()
        return CastConnectReceiverStatus(
            sdkInitialized = receiverContext != null,
            started = started,
            configuredReceiverApplicationId = configuredId,
            receiverApplicationIdMatches = configuredId == YFUSE_CAST_RECEIVER_APPLICATION_ID,
            developerConsoleAssociation = developerConsoleAssociation,
            loadHandlerInstalled = loadHandler != null,
            mediaSessionAttached = mediaSessionAttached,
            lastFailure = lastFailure,
        )
    }

    private class ReceiverLoadCallback : MediaLoadCommandCallback() {
        override fun onLoad(
            senderId: String?,
            requestData: MediaLoadRequestData,
        ): Task<MediaLoadRequestData> {
            val mediaInfo = requestData.mediaInfo ?: return loadFailure()
            val envelope =
                CastConnectLoadEnvelope(
                    senderId = senderId,
                    entity = mediaInfo.entity,
                    contentId = mediaInfo.contentId,
                    contentUrl = mediaInfo.contentUrl,
                    contentType = mediaInfo.contentType,
                    title = mediaInfo.metadata?.getString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE),
                    autoplay = requestData.autoplay ?: true,
                    positionMs = requestData.currentTime,
                    credentialsSupplied = !requestData.credentials.isNullOrBlank(),
                )
            val request = CastConnectLoadMapper.map(envelope) ?: return loadFailure()
            val accepted = runCatching { loadHandler?.onLoad(request) == true }.getOrDefault(false)
            if (!accepted) return loadFailure()

            val current = receiverContext ?: return loadFailure()
            return runCatching {
                current.mediaManager.setDataFromLoad(requestData)
                current.mediaManager.broadcastMediaStatus()
                Tasks.forResult(requestData)
            }.getOrElse { loadFailure() }
        }

        private fun loadFailure(): Task<MediaLoadRequestData> =
            Tasks.forException(
                MediaException(
                    MediaError
                        .Builder()
                        .setDetailedErrorCode(DetailedErrorCode.LOAD_FAILED)
                        .setReason(MediaError.ERROR_REASON_INVALID_REQUEST)
                        .build(),
                ),
            )
    }
}

/** Manifest-instantiated receiver options provider. Keep this public and no-arg. */
class YfuseCastReceiverOptionsProvider : ReceiverOptionsProvider {
    override fun getOptions(context: Context): CastReceiverOptions =
        CastReceiverOptions
            .Builder(context)
            .setVersionCode(CAST_RECEIVER_PROTOCOL_VERSION)
            .setStatusText("Yfuse")
            .build()

    private companion object {
        const val CAST_RECEIVER_PROTOCOL_VERSION = 1
    }
}

private const val MAX_CAST_CONNECT_MEDIA_URL_CHARS = 16_384
