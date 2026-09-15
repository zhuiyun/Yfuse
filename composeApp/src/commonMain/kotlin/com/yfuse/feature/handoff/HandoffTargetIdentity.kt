package com.yfuse.feature.handoff

import com.yfuse.core.handoff.HandoffMedia

/** Track indexes belong to the originating physical version, not an alternate server copy. */
internal fun HandoffMedia.forHandoffTarget(
    serverId: String,
    itemId: String,
    mediaKey: String,
    mediaSourceId: String?,
): HandoffMedia {
    val samePhysicalVersion =
        this.serverId == serverId &&
            this.itemId == itemId &&
            this.mediaSourceId != null &&
            this.mediaSourceId == mediaSourceId
    return copy(
        serverId = serverId,
        itemId = itemId,
        mediaKey = mediaKey,
        mediaSourceId = mediaSourceId,
        audioTrackIndex = audioTrackIndex.takeIf { samePhysicalVersion },
        subtitleTrackIndex = subtitleTrackIndex.takeIf { samePhysicalVersion },
        secondarySubtitleTrackIndex = secondarySubtitleTrackIndex.takeIf { samePhysicalVersion },
    )
}
