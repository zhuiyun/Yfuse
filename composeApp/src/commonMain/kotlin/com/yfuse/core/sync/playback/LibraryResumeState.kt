package com.yfuse.core.sync.playback

/** A concrete library record is authoritative across history, detail and playback launch.
 * Portable component records may seed a missing item, but never replace its synced progress.
 */
internal fun libraryResumeState(
    direct: PlaybackStateRecord?,
    portable: List<PlaybackStateRecord>,
): PlaybackStateRecord? {
    if (direct != null) return direct
    return portable
        .filter { it.serverId == null && it.serverItemId == null }
        .fold<PlaybackStateRecord, PlaybackStateRecord?>(null) { selected, record ->
            selected?.let { PlaybackConflictResolver.chooseState(it, record) } ?: record
        }
}
