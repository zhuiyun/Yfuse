package com.yfuse.core2.android

import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.playbackDiagnosticTrace
import com.yfuse.core2.api.YMediaItem

/** Only explicit opaque identities and observed phase facts enter telemetry, never source credentials. */
internal inline fun <T> yCoreStartupStage(
    stage: String,
    item: YMediaItem? = null,
    decoderName: String? = null,
    operation: () -> T,
): T {
    val startedNs = System.nanoTime()
    return try {
        operation()
    } finally {
        AppLog.info(
            category = "player.core2",
            event = "startup_stage_timing",
            // The log store deduplicates on message/event, not attributes. Keep each phase
            // and playback attempt distinguishable without exposing the source identity.
            message = "YCore startup stage $stage completed (${playbackDiagnosticTrace(item?.playbackSessionId)})",
            attributes =
                mapOf(
                    "stage" to stage,
                    "itemId" to item?.id.orEmpty(),
                    "serverId" to item?.providerKey.orEmpty(),
                    "sessionId" to item?.playbackSessionId.orEmpty(),
                    "playbackTrace" to playbackDiagnosticTrace(item?.playbackSessionId),
                    "decoder" to decoderName.orEmpty(),
                    "elapsedMs" to ((System.nanoTime() - startedNs) / 1_000_000L).toString(),
                ),
        )
    }
}
