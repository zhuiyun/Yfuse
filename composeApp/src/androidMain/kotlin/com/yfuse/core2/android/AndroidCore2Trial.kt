package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import com.yfuse.feature.player.VideoEngine

/**
 * Separate experiment switch for Scheme C.
 *
 * This intentionally does NOT extend or serialize the legacy PlayerEngine enum. Core2 rollout can
 * therefore be enabled/disabled independently without migrating old engine preferences or teaching
 * Legacy failure memory about a fake Exo/mpv identity.
 */
internal class AndroidCore2TrialPreferences(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    fun clear() {
        preferences.edit().remove(KEY_ENABLED).apply()
    }
}

/** Thin construction boundary used by the existing VideoEngine-based PlayerRoot during migration. */
internal object AndroidCore2TrialFactory {
    fun create(
        context: Context,
        request: YPlayerOpenRequest,
    ): VideoEngine =
        YPlayerVideoEngineAdapter(
            AndroidAdaptiveCore2YPlayer(
                context = context.applicationContext,
                request = request,
            ),
        )
}

private const val PREFERENCES_NAME = "yfuse_ycore2_trial"
private const val KEY_ENABLED = "enabled"
