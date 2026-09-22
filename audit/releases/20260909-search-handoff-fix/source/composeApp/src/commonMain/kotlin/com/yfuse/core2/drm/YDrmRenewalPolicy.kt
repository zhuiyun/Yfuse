package com.yfuse.core2.drm

/** MediaDrm key-status policy kept pure so expiry behavior is deterministic and testable. */
fun shouldRenewDrmKeys(
    eventRequested: Boolean,
    status: Map<String, String>,
    renewalThresholdSeconds: Long = DEFAULT_KEY_RENEWAL_THRESHOLD_SECONDS,
): Boolean {
    require(renewalThresholdSeconds >= 0L)
    if (eventRequested) return true
    val remainingSeconds =
        status.entries
            .filter { (key, _) ->
                key.equals(LICENSE_DURATION_REMAINING, ignoreCase = true) ||
                    key.equals(PLAYBACK_DURATION_REMAINING, ignoreCase = true)
            }.mapNotNull { (_, value) -> value.trim().toLongOrNull() }
            .minOrNull()
    return remainingSeconds != null && remainingSeconds <= renewalThresholdSeconds
}

private const val DEFAULT_KEY_RENEWAL_THRESHOLD_SECONDS = 60L
private const val LICENSE_DURATION_REMAINING = "LicenseDurationRemaining"
private const val PLAYBACK_DURATION_REMAINING = "PlaybackDurationRemaining"
