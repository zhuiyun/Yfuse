package com.yfuse.core.notification

import android.app.Notification
import android.os.Bundle

/**
 * 实况通知 — Android 16's live updates.
 *
 * The system lifts an ongoing notification into the status bar chip and the top of the shade when
 * it has a title and a progress style, is not colourised, asks for it through this extra, and the
 * app holds POST_PROMOTED_NOTIFICATIONS (declared in the manifest; the user can still turn it off).
 * The extra rather than a builder call: Android 16 reads the extra, while
 * `Notification.Builder.setRequestPromotedOngoing` only arrived in a later release.
 */
private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

/** Callers build live updates only from API 36; Android 15 and below keep the notifications they had. */
internal fun Notification.Builder.requestPromotedOngoing(): Notification.Builder =
    addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })

/**
 * Colours for the parts of a live-update bar that carry meaning; everything else takes the
 * notification's own accent, which the system also adjusts for contrast.
 */
internal object LiveUpdateColors {
    /** A download that failed. */
    const val FAILED = 0xFFE5484D.toInt()

    /** A chapter point on a cast bar — the warm mark the player's own bar uses for chapters. */
    const val CHAPTER = 0xFFF0C27A.toInt()
}
