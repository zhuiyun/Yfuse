package com.yfuse.core.sync

/** Do not light a readiness ring for stale, unknown or contradictory room telemetry. */
internal fun WatchParticipant.cinemaReady(): Boolean = statusKnown && ready && mediaAvailable && !buffering
