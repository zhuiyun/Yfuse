package com.yfuse.feature.player

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.OpenableColumns
import android.widget.Toast
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * 打开方式 → Yfuse: the one exported door for videos and links from other apps.
 *
 * [PlayerActivity] stays unexported. Its launch is an in-process token, and an intent from outside
 * could otherwise aim it at a queue, a server or a token of its choosing. This activity reads the
 * single thing it accepts — a `content://`, `file://` or `http(s)://` video from ACTION_VIEW, or an
 * `http(s)` link shared as text — turns it into an entry with no server identity
 * ([externalPlaybackItem]) and hands that to the player through [PlayerActivity.intent], the way
 * the library does. Nothing else of the incoming intent travels on: no extras, no flags, and for a
 * `content://` video only the read grant its sender gave. Optional provider metadata has a bounded
 * background lookup; this activity keeps the incoming grant alive until the player takes it.
 *
 * Every check here runs on the intent itself, not on the manifest filter, because an explicit
 * intent reaches an exported activity without passing any filter.
 */
class ExternalPlaybackActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val metadata = ExternalMetadataLookup()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch {
            val refusal =
                try {
                    open(intent)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: RuntimeException) {
                    // The class name only: a platform message about a URI grant quotes the URI.
                    AppLog.warning(
                        category = "feature.player",
                        event = "external_playback_failed",
                        message = "An outside video could not be handed to the player",
                        attributes = mapOf("exception" to error.javaClass.simpleName),
                    )
                    "无法打开这个视频"
                }
            refusal?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_SHORT).show() }
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Hands [incoming] to the player. Returns why it could not, for the viewer, or null. */
    private suspend fun open(incoming: Intent?): String? {
        if (incoming == null) return UNSUPPORTED_MESSAGE
        val action = incoming.action
        val target =
            externalPlaybackTarget(
                action = action,
                data = incoming.dataString,
                type = incoming.type,
                sharedText = if (action == Intent.ACTION_SEND) incoming.textExtra(Intent.EXTRA_TEXT) else null,
            )
        if (target == null) {
            logRefusal(action, incoming.dataString, reason = "unsupported")
            return UNSUPPORTED_MESSAGE
        }
        val uri = Uri.parse(target.uri)
        val offeredTitle = incoming.textExtra(Intent.EXTRA_TITLE) ?: incoming.textExtra(PLAYER_TITLE_EXTRA)
        val title =
            when (target.source) {
                ExternalPlaybackSource.Content -> {
                    if (uri.belongsToThisApp()) {
                        logRefusal(action, target.uri, reason = "own_provider")
                        return UNSUPPORTED_MESSAGE
                    }
                    val documentName = if (offeredTitle.isNullOrBlank()) contentDisplayName(uri) else null
                    externalPlaybackTitle(offeredTitle, externalFileTitle(documentName))
                }
                ExternalPlaybackSource.File -> {
                    if (!uri.isReadableOutsideFile()) {
                        logRefusal(action, target.uri, reason = "unreadable_file")
                        return "无法读取这个文件"
                    }
                    externalPlaybackTitle(offeredTitle, externalFileTitle(uri.lastPathSegment))
                }
                ExternalPlaybackSource.Web -> {
                    if (externalStreamTargetsLocalNetwork(target.uri) && !confirmLocalNetworkTarget()) {
                        logRefusal(action, target.uri, reason = "local_network_declined")
                        return null
                    }
                    externalPlaybackTitle(offeredTitle, externalStreamTitle(target.uri))
                }
            }
        val preferences = runCatching { GlobalContext.get().get<ThemePreferences>() }.getOrNull()
        scope.ensureActive()
        if (isFinishing || isDestroyed) return null
        // A new task: the player joins Yfuse's own, where a live player is reused rather than
        // doubled, instead of the sender's task this activity is about to leave.
        val launch =
            PlayerActivity
                .intent(
                    context = this,
                    items = listOf(externalPlaybackItem(url = target.uri, title = title)),
                    startIndex = 0,
                    startPositionMs = 0L,
                    engine = preferences?.engine?.value ?: PlayerEngine.Exo,
                    decoder = preferences?.decoder?.value ?: DecoderMode.Hardware,
                    autoNext = false,
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (target.source == ExternalPlaybackSource.Content && holdsReadGrant(uri)) {
            // The sender's grant belongs to this activity and ends when it does. Passing the
            // document on as ClipData with the read flag gives the player its own grant, which lasts
            // as long as the player does; the launch itself still carries only its token.
            launch.clipData = ClipData.newRawUri(GRANT_CLIP_LABEL, uri)
            launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(launch)
        } catch (error: RuntimeException) {
            PlayerActivity.discardLaunch(launch)
            throw error
        }
        AppLog.info(
            category = "feature.player",
            event = "external_playback_opened",
            message = "An outside video was handed to the player",
            attributes =
                mapOf(
                    "via" to externalPlaybackVia(action),
                    "source" to target.source.name.lowercase(),
                ),
        )
        return null
    }

    /**
     * Another app asked Yfuse to open an address on this device or the local network. Fetching it
     * would reach inside the network on that app's behalf - a router page, another app's loopback
     * server - so the viewer decides. Dismissing the question declines.
     */
    private suspend fun confirmLocalNetworkTarget(): Boolean {
        val answer = CompletableDeferred<Boolean>()
        val dialog =
            AlertDialog
                .Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("打开本机或局域网中的视频？")
                .setMessage("另一个应用请 Yfuse 打开一个指向这台设备或局域网的链接。只有在你信任这个应用时才继续。")
                .setPositiveButton("打开") { _, _ -> answer.complete(true) }
                .setNegativeButton("取消") { _, _ -> answer.complete(false) }
                .setOnCancelListener { answer.complete(false) }
                .show()
        return try {
            answer.await()
        } finally {
            dialog.dismiss()
        }
    }

    /**
     * Only a grant this process holds can be passed on: asking to re-grant one it lacks makes
     * startActivity throw, and a document shared without a grant is either readable anyway (an open
     * provider) or not at all.
     */
    private fun holdsReadGrant(uri: Uri): Boolean =
        checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * An outside app has no business pointing the player at this app's own provider: whatever it
     * serves, it serves to Yfuse, not to whoever sent the intent.
     */
    private fun Uri.belongsToThisApp(): Boolean {
        val authority = authority?.substringAfterLast('@') ?: return true
        return authority == packageName || authority.startsWith("$packageName.")
    }

    /**
     * A `file://` path is honoured only where the platform lets this app read it — rarely, now that
     * shared storage needs a permission Yfuse does not hold — and never inside the app's private
     * directories, which an outside app must not be able to aim the player at.
     */
    private suspend fun Uri.isReadableOutsideFile(): Boolean {
        val file = path?.let(::File)?.takeIf(File::isAbsolute) ?: return false
        val privatePaths = listOfNotNull(applicationInfo.dataDir, applicationInfo.deviceProtectedDataDir)
        return metadata.read {
            val canonical = file.canonicalFile
            val privateRoots = privatePaths.map { File(it).canonicalFile }
            privateRoots.none { canonical.startsWith(it) } && canonical.isFile && canonical.canRead()
        } == true
    }

    /** The document's own name, for the title; the provider may refuse, and then there is none. */
    private suspend fun contentDisplayName(uri: Uri): String? {
        val resolver = applicationContext.contentResolver
        return metadata.read {
            resolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                }
        }
    }

    /** Scheme and route only: the address itself, and anything derived from it, stays out of logs. */
    private fun logRefusal(
        action: String?,
        address: String?,
        reason: String,
    ) {
        AppLog.warning(
            category = "feature.player",
            event = "external_playback_rejected",
            message = "An outside playback request was refused",
            attributes =
                mapOf(
                    "via" to externalPlaybackVia(action),
                    "scheme" to externalPlaybackSchemeLabel(address),
                    "reason" to reason,
                ),
        )
    }

    private companion object {
        const val UNSUPPORTED_MESSAGE = "Yfuse 只能打开视频文件或 http(s) 视频链接"

        /** The title extra players have long accepted from browsers and download managers. */
        const val PLAYER_TITLE_EXTRA = "title"
        const val GRANT_CLIP_LABEL = "video"
    }
}

/** Where an outside video lives, which decides how the player may read it. */
internal enum class ExternalPlaybackSource {
    /** A document another app lends through its provider; readable only with the grant it sends. */
    Content,

    /** A path; see [ExternalPlaybackActivity] for when one can still be read. */
    File,

    /** An `http(s)` address, fetched with nothing added to it. */
    Web,
}

internal data class ExternalPlaybackTarget(
    val uri: String,
    val source: ExternalPlaybackSource,
)

/**
 * What an incoming intent asks to play, decided from its parts alone, or null to refuse it.
 *
 * ACTION_VIEW plays its data: a `content://` document, an absolute `file:///` path or an `http(s)`
 * address. A declared type has to be `video/…`; an explicit intent may leave it out, and then the
 * address alone decides. ACTION_SEND plays the first `http(s)` link in its text. Every other
 * action and scheme — `intent:`, `javascript:`, `data:`, `smb://`, `rtsp://` and the rest — is
 * refused, as is anything padded with whitespace or control characters.
 */
internal fun externalPlaybackTarget(
    action: String?,
    data: String?,
    type: String?,
    sharedText: String?,
): ExternalPlaybackTarget? =
    when (action) {
        Intent.ACTION_VIEW -> viewedPlaybackTarget(data, type)
        Intent.ACTION_SEND -> sharedPlaybackTarget(sharedText, type)
        else -> null
    }

private fun viewedPlaybackTarget(
    data: String?,
    type: String?,
): ExternalPlaybackTarget? {
    if (type != null && !type.startsWith("video/", ignoreCase = true)) return null
    val address = data?.takeIf { it.isNotEmpty() && it.length <= MAX_EXTERNAL_STREAM_URL_CHARS } ?: return null
    if (address.any { it.isWhitespace() || it.isISOControl() }) return null
    return when {
        // Exact lower-case schemes, as the manifest filter matches them and ContentResolver reads them.
        address.startsWith(CONTENT_PREFIX) ->
            address
                .takeIf { address.substring(CONTENT_PREFIX.length).substringBefore('/').isNotEmpty() }
                ?.let { ExternalPlaybackTarget(it, ExternalPlaybackSource.Content) }
        address.startsWith(FILE_PREFIX) ->
            address
                .takeIf { it.length > LOCAL_FILE_PREFIX.length && it.startsWith(LOCAL_FILE_PREFIX) }
                ?.let { ExternalPlaybackTarget(it, ExternalPlaybackSource.File) }
        else -> webPlaybackTarget(address)
    }
}

private fun sharedPlaybackTarget(
    sharedText: String?,
    type: String?,
): ExternalPlaybackTarget? {
    if (type != null && !type.startsWith("text/", ignoreCase = true)) return null
    return sharedText?.let(::firstSharedWebLink)?.let(::webPlaybackTarget)
}

private fun webPlaybackTarget(address: String): ExternalPlaybackTarget? =
    (parseExternalStreamUrl(address) as? ExternalStreamUrl.Accepted)
        ?.let { ExternalPlaybackTarget(it.url, ExternalPlaybackSource.Web) }

/** How the request arrived, as a fixed label; the raw action string comes from another app. */
internal fun externalPlaybackVia(action: String?): String =
    when (action) {
        Intent.ACTION_VIEW -> "view"
        Intent.ACTION_SEND -> "send"
        else -> "other"
    }

/** The scheme of [address] when it is one this door knows, a fixed label otherwise. */
internal fun externalPlaybackSchemeLabel(address: String?): String {
    val scheme = address?.substringBefore(':', missingDelimiterValue = "")?.lowercase().orEmpty()
    return when {
        scheme.isEmpty() -> "none"
        scheme in setOf("content", "file", "http", "https") -> scheme
        else -> "other"
    }
}

private const val CONTENT_PREFIX = "content://"
private const val FILE_PREFIX = "file:"
private const val LOCAL_FILE_PREFIX = "file:///"

/** An extra another app put in the intent; unreadable parcels are treated as absent. */
private fun Intent.textExtra(name: String): String? = runCatching { getCharSequenceExtra(name)?.toString() }.getOrNull()
