package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod

/** A queue entry with one version, its stream URLs and its server approval spelled out. */
internal fun ladderItem(
    hls: String = "hls",
    mp4: String = "mp4",
    approved: Boolean = true,
    playMethod: PlaybackMethod = PlaybackMethod.DirectPlay,
    dolbyVision: Boolean = false,
    dolbyAtmos: Boolean = false,
    disc: Boolean = false,
    versionApproved: Boolean = false,
    serverId: String? = "s1",
): PlayerMediaItem {
    val version =
        PlayerMediaVersion(
            id = "v1",
            label = "v1",
            detail = "",
            url = "direct",
            transcodeUrl = hls,
            fallbackTranscodeUrl = mp4,
            discSource = disc,
            dolbyVision = dolbyVision,
            dolbyAtmos = dolbyAtmos,
            playMethod = playMethod,
            serverTranscodeSupported = versionApproved,
        )
    return PlayerMediaItem(
        id = "item",
        url = "direct",
        transcodeUrl = hls,
        title = "第 1 集",
        fallbackTranscodeUrl = mp4,
        serverId = serverId,
        versions = listOf(version),
        versionId = version.id,
        playMethod = playMethod,
        serverTranscodeSupported = approved,
    )
}
