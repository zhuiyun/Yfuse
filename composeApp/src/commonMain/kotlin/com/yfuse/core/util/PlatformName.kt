package com.yfuse.core.util

/**
 * The platform this build runs on, as reported to remote services (Plex headers, handoff).
 *
 * Common code used to spell "Android" out; a second target would have advertised itself as
 * Android too, so the name now comes from the platform source set.
 */
expect fun platformName(): String
