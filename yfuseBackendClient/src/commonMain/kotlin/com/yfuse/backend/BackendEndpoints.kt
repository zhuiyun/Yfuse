package com.yfuse.backend

/** Deployment addresses live only in the removable backend client. */
object BackendEndpoints {
    const val ORIGIN = "https://47.112.219.60"
    const val UPDATE_MANIFEST = "$ORIGIN/yfuse/update-v2.json"
    const val PLAYBACK_POLICY = "$ORIGIN/yfuse/playback-policy-v1.json"
    const val TMDB_PROXY = "$ORIGIN/api/v1/tmdb"
    const val CALENDAR = "$ORIGIN/api/v1/calendar/schedules"
}
