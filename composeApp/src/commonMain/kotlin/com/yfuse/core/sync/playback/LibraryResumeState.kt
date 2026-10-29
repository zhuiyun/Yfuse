package com.yfuse.core.sync.playback

/** Resolve every known copy with the shared progress ordering, including component playback.
 */
internal fun libraryResumeState(
    direct: PlaybackStateRecord?,
    portable: List<PlaybackStateRecord>,
): PlaybackStateRecord? =
    (listOfNotNull(direct) + portable)
        .fold<PlaybackStateRecord, PlaybackStateRecord?>(null) { selected, record ->
            selected?.let { PlaybackConflictResolver.chooseState(it, record) } ?: record
        }
