package com.yfuse.feature.player

/**
 * Whether the measured link cannot carry the source. Unknown on either side is not a shortfall:
 * a missing estimate must never be reported as a slow network.
 */
internal fun networkCannotCarrySource(
    networkBitsPerSecond: Long,
    sourceBitsPerSecond: Long,
): Boolean = networkBitsPerSecond in 1L until sourceBitsPerSecond

/**
 * What a viewer waiting for the first frame is told when the link is the reason. A 13 Mbps title
 * on a 2 Mbps server used to sit under 「正在准备画面」 until the viewer gave up, with nothing to
 * say that waiting would not help.
 */
internal fun networkShortfallMessage(
    networkBitsPerSecond: Long,
    sourceBitsPerSecond: Long,
): String? {
    if (!networkCannotCarrySource(networkBitsPerSecond, sourceBitsPerSecond)) return null
    return "网速约 ${networkBitsPerSecond.asMbps()}，低于片源 ${sourceBitsPerSecond.asMbps()}"
}

private fun Long.asMbps(): String {
    val tenths = (this + 50_000L) / 100_000L
    return "${tenths / 10}.${tenths % 10} Mbps"
}
