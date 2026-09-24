package com.yfuse.core2.recovery

/**
 * A route executor that keeps the failure behind its Failed state for the router that owns it.
 *
 * [com.yfuse.core2.api.YPlayerState] carries only a category and a localized message. Recovery
 * needs what the typed [com.yfuse.core2.api.YPlaybackException] says beyond that - whether the
 * failure is deterministic, and the stage and safe detail the router publishes once no route is
 * left - and whether the executor simply ran out of its start deadline instead of failing on the
 * media. An executor that does not implement this is treated as reporting category only.
 */
interface YPlaybackFailureReporter {
    /**
     * The failure published with the most recent Failed state; null before the first one.
     *
     * Assigned before that state is published, so a collector reading it on the Failed edge sees
     * the failure of the same edge. Never render its message: it may contain a source URL.
     */
    val lastPlaybackFailure: Throwable?
}
