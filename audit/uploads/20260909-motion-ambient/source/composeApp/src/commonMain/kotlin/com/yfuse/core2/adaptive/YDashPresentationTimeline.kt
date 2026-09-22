package com.yfuse.core2.adaptive

/** Selects an authored Period on the public presentation timeline, including exact boundaries. */
fun YDashManifest.periodForPositionUs(positionUs: Long): YDashPeriod {
    require(positionUs >= 0L)
    require(periods.isNotEmpty()) { "DASH presentation has no Period timeline" }
    return periods.firstOrNull { period ->
        positionUs < period.startUs || period.endUs?.let { positionUs < it } != false
    } ?: periods.last()
}

/**
 * A controlled reopen gets one complete Period with its own initialization data. The player maps
 * its local clock back to [YDashPeriod.startUs]; segment presentationTimeOffset remains authored.
 */
fun YDashManifest.manifestForPeriod(period: YDashPeriod): YDashManifest {
    require(period in periods)
    require(period.durationUs != null || isLive) { "Static DASH Period duration is unknown" }
    return copy(
        mediaPresentationDurationUs = period.durationUs,
        periodStartUs = 0L,
        representations = period.representations,
        periods = listOf(period.copy(startUs = 0L)),
    )
}
