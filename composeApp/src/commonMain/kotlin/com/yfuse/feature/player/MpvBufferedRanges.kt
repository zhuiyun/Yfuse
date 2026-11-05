package com.yfuse.feature.player

import com.yfuse.core2.api.YBufferRangeCoverage
import com.yfuse.core2.api.YBufferRangeSource
import com.yfuse.core2.api.YBufferSnapshot
import com.yfuse.core2.api.YBufferedRange
import com.yfuse.core2.api.normalizeBufferedRanges
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** mpv's node STRING conversion is JSON; unsupported builds return Unknown, never guessed ranges. */
internal fun mpvBufferSnapshot(
    raw: String?,
    sessionId: String,
    generation: Long,
    durationMs: Long,
): YBufferSnapshot {
    val unknown =
        YBufferSnapshot(sessionId = sessionId, generation = generation, source = YBufferRangeSource.MpvDemuxer)
    if (raw == null || raw.length > 65_536) return unknown
    return runCatching {
        val node = Json.parseToJsonElement(raw) as? JsonObject ?: return unknown
        val values = node["seekable-ranges"] as? JsonArray ?: return unknown
        val ranges =
            values.take(256).mapNotNull { value ->
                val entry = value as? JsonObject ?: return@mapNotNull null
                val start = (entry["start"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                val end = (entry["end"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                if (!start.isFinite() ||
                    !end.isFinite() ||
                    start < 0 ||
                    end <= start ||
                    end > Long.MAX_VALUE / 1_000.0
                ) {
                    return@mapNotNull null
                }
                YBufferedRange((start * 1_000).toLong(), (end * 1_000).toLong())
            }
        unknown.copy(
            coverage = YBufferRangeCoverage.SeekableRanges,
            ranges = normalizeBufferedRanges(ranges, durationMs),
        )
    }.getOrDefault(unknown)
}
