package com.yfuse.feature.player

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.NativePlaybackComponent
import com.yfuse.core.playback.classifyNativePlaybackCrash
import java.io.InputStream
import java.security.MessageDigest

/**
 * Records only credential-free native playback context. Native SIGSEGV cannot safely be caught in
 * process, so Android's historical exit record is consumed on the next launch.
 */
internal object AndroidNativeCrashMonitor {
    private const val PREFS = "yfuse_native_crash_v1"
    private const val FAILURE_THRESHOLD = 2
    private const val MAX_TRACE_BYTES = 256 * 1024
    private const val ACTIVE_MAX_AGE_MS = 12L * 60L * 60L * 1_000L

    private lateinit var appContext: Context

    @Synchronized
    fun initialize(context: Context) {
        appContext = context.applicationContext
        consumePreviousNativeExit()
    }

    @Synchronized
    fun safeDecoderMode(
        engine: PlayerEngine,
        requested: DecoderMode,
        capabilitySignature: String,
    ): DecoderMode {
        if (!isBlocked(componentFor(engine), engine, requested, capabilitySignature)) return requested
        val alternative =
            when (requested) {
                DecoderMode.Software -> DecoderMode.Hardware
                DecoderMode.Hardware,
                DecoderMode.Auto,
                -> DecoderMode.Software
            }
        AppLog.warning(
            category = "player.native_crash",
            event = "decoder_path_isolated",
            message = "A repeatedly crashing native engine and decoder path was isolated",
            attributes =
                mapOf(
                    "engine" to engine.name,
                    "blockedDecoder" to requested.name,
                    "replacementDecoder" to alternative.name,
                    "capability" to digest(capabilitySignature).take(12),
                ),
        )
        return alternative
    }

    @Synchronized
    fun isYCoreDemuxBlocked(
        decoderMode: DecoderMode,
        capabilitySignature: String,
    ): Boolean =
        isBlocked(
            NativePlaybackComponent.YCoreDemux,
            PlayerEngine.Exo,
            decoderMode,
            capabilitySignature,
        )

    /** Must run before native construction so a constructor crash still leaves useful context. */
    @Synchronized
    fun arm(
        component: NativePlaybackComponent,
        engine: PlayerEngine,
        decoderMode: DecoderMode,
        capabilitySignature: String,
        media: PlayerMediaItem?,
    ) {
        prefs()
            .edit()
            .putString("active.component", component.name)
            .putString("active.engine", engine.name)
            .putString("active.decoder", decoderMode.name)
            .putString("active.capability", digest(capabilitySignature))
            .putString("active.media", media?.privacySafeMediaFingerprint().orEmpty())
            .putString(
                "active.scheme",
                media
                    ?.url
                    ?.substringBefore(':')
                    ?.lowercase()
                    .orEmpty(),
            ).putLong("active.started", System.currentTimeMillis())
            .apply()
    }

    /** A normal, rendering teardown proves the exact path worked and breaks the crash streak. */
    @Synchronized
    fun disarm(successful: Boolean) {
        val preferences = prefs()
        if (successful) activeKey(preferences)?.let { preferences.edit().remove(countKey(it)).apply() }
        preferences.edit().removeActiveContext().apply()
    }

    private fun consumePreviousNativeExit() {
        val preferences = prefs()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            preferences.edit().removeActiveContext().apply()
            return
        }
        val activityManager = appContext.getSystemService(ActivityManager::class.java) ?: return
        val lastConsumed = preferences.getLong("last.exit.timestamp", 0L)
        val newest =
            runCatching {
                activityManager
                    .getHistoricalProcessExitReasons(appContext.packageName, 0, 12)
                    .filter { it.timestamp > lastConsumed }
                    .maxByOrNull { it.timestamp }
            }.getOrNull()
        if (newest == null) {
            preferences.edit().removeActiveContext().apply()
            return
        }
        preferences.edit().putLong("last.exit.timestamp", newest.timestamp).apply()
        if (newest.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) {
            preferences.edit().removeActiveContext().apply()
            return
        }

        val active = activeKey(preferences)
        val activeStarted = preferences.getLong("active.started", 0L)
        val recent = active != null && newest.timestamp - activeStarted in 0L..ACTIVE_MAX_AGE_MS
        val traceComponent =
            runCatching { newest.traceInputStream?.use(::readBoundedTrace) }
                .getOrNull()
                ?.let(::classifyNativePlaybackCrash)
                ?: NativePlaybackComponent.Unknown
        val attributed =
            active?.takeIf { recent }?.copy(
                component =
                    traceComponent.takeIf { it != NativePlaybackComponent.Unknown }
                        ?: active.component,
            )
        if (attributed != null && attributed.component != NativePlaybackComponent.Unknown) {
            val key = countKey(attributed)
            val count = (preferences.getInt(key, 0) + 1).coerceAtMost(100)
            preferences.edit().putInt(key, count).apply()
            AppLog.warning(
                category = "player.native_crash",
                event = "previous_native_crash_classified",
                message = "The previous native playback crash was classified on startup",
                attributes =
                    mapOf(
                        "component" to attributed.component.name,
                        "engine" to attributed.engine.name,
                        "decoder" to attributed.decoderMode.name,
                        "count" to count.toString(),
                        "media" to preferences.getString("active.media", "").orEmpty(),
                        "scheme" to preferences.getString("active.scheme", "").orEmpty(),
                    ),
            )
        }
        preferences.edit().removeActiveContext().apply()
    }

    private fun isBlocked(
        component: NativePlaybackComponent,
        engine: PlayerEngine,
        decoderMode: DecoderMode,
        capabilitySignature: String,
    ): Boolean =
        prefs().getInt(
            countKey(
                ActiveKey(
                    component = component,
                    engine = engine,
                    decoderMode = decoderMode,
                    capabilityDigest = digest(capabilitySignature),
                ),
            ),
            0,
        ) >= FAILURE_THRESHOLD

    private fun activeKey(preferences: android.content.SharedPreferences): ActiveKey? {
        val component = preferences.getString("active.component", null)?.enumOrNull<NativePlaybackComponent>()
        val engine = preferences.getString("active.engine", null)?.enumOrNull<PlayerEngine>()
        val decoder = preferences.getString("active.decoder", null)?.enumOrNull<DecoderMode>()
        val capability = preferences.getString("active.capability", null)?.takeIf(String::isNotBlank)
        return if (component != null && engine != null && decoder != null && capability != null) {
            ActiveKey(component, engine, decoder, capability)
        } else {
            null
        }
    }

    private fun android.content.SharedPreferences.Editor.removeActiveContext() =
        remove("active.component")
            .remove("active.engine")
            .remove("active.decoder")
            .remove("active.capability")
            .remove("active.media")
            .remove("active.scheme")
            .remove("active.started")

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun countKey(key: ActiveKey): String =
        "count.${key.component.name}.${key.engine.name}.${key.decoderMode.name}.${key.capabilityDigest}"

    private fun componentFor(engine: PlayerEngine): NativePlaybackComponent =
        when (engine) {
            PlayerEngine.Mpv -> NativePlaybackComponent.Mpv
            PlayerEngine.Mdk -> NativePlaybackComponent.Mdk
            PlayerEngine.Exo -> NativePlaybackComponent.Unknown
        }

    private fun readBoundedTrace(input: InputStream): String {
        val buffer = ByteArray(8 * 1024)
        val output = StringBuilder()
        var remaining = MAX_TRACE_BYTES
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            output.append(buffer.decodeToString(endIndex = read))
            remaining -= read
        }
        return output.toString()
    }

    private fun PlayerMediaItem.privacySafeMediaFingerprint(): String =
        digest(listOf(serverId, id, versionId).joinToString("|"))
            .take(16)

    private fun digest(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private inline fun <reified T : Enum<T>> String.enumOrNull(): T? = enumValues<T>().firstOrNull { it.name == this }

    private data class ActiveKey(
        val component: NativePlaybackComponent,
        val engine: PlayerEngine,
        val decoderMode: DecoderMode,
        val capabilityDigest: String,
    )
}
