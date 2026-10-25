package com.yfuse.feature.widgets

import com.yfuse.core.widget.WidgetMedia
import kotlin.math.roundToInt

/** Select actual neighbours once each, including at the ends of a short collection. */
internal fun orbitNeighbourIndices(
    current: Int,
    count: Int,
): List<Int> {
    if (count <= 1) return emptyList()
    val anchor = current.coerceIn(0, count - 1)
    return listOf(anchor + 1, anchor - 1, anchor + 2, anchor - 2)
        .filter { it in 0 until count }
        .distinct()
        .take(2)
}

/** Unknown/invalid progress must never look like a completed or partly watched film. */
internal fun orbitProgress(item: WidgetMedia): Float? =
    if (item.played == true) {
        1f
    } else {
        item.progress?.takeIf { it.isFinite() && it in 0f..1f }
    }

internal fun orbitRemainingMinutes(item: WidgetMedia): Int? {
    val progress = orbitProgress(item)?.takeIf { it > 0f && it < 1f } ?: return null
    val duration = item.durationMinutes?.takeIf { it > 0 } ?: return null
    // Duration is only minute-precision; present an explicitly approximate minute count.
    return (duration.toDouble() * (1.0 - progress.toDouble())).roundToInt().coerceAtLeast(1)
}

internal fun orbitEpisodeLabel(item: WidgetMedia): String =
    item.episode
        ?.takeIf { it > 0 }
        ?.let { episode ->
            "${item.season?.takeIf { it >= 0 }?.let { "S$it · " }.orEmpty()}第 $episode 集"
        }.orEmpty()

/** Keep the same film when refreshed data changes order; clamp safely after removal. */
internal fun orbitAnchorIndex(
    selectedId: String?,
    previousIndex: Int,
    items: List<WidgetMedia>,
): Int {
    if (items.isEmpty()) return 0
    return items.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
        ?: previousIndex.coerceIn(items.indices)
}

internal fun orbitPrimaryFrame(single: Boolean): KineticPosterFrame =
    if (single) {
        KineticPosterFrame(.15f, .05f, .70f, .70f)
    } else {
        KineticPosterFrame(.015f, .135f, .61f, .61f)
    }

internal fun orbitSatelliteFrame(slot: Int): KineticPosterFrame =
    if (slot == 0) {
        KineticPosterFrame(.70f, .025f, .18f, .27f, tilt = 7f)
    } else {
        KineticPosterFrame(.69f, .445f, .20f, .30f, tilt = 10f)
    }
