package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.designsystem.TipsStore

/** Which 情境提示 have retired, and the day the last one showed; see [com.yfuse.core.designsystem.TipsState]. */
class TipsPreferences(
    private val settings: Settings,
) : TipsStore {
    private companion object {
        const val KEY_RETIRED_PREFIX = "tips.retired."
        const val KEY_LAST_SHOWN_DAY = "tips.lastShownDay"
    }

    override fun isRetired(id: String): Boolean = settings.getBoolean(KEY_RETIRED_PREFIX + id, false)

    override fun retire(id: String) = settings.putBoolean(KEY_RETIRED_PREFIX + id, true)

    override fun lastShownDay(): String? = settings.getStringOrNull(KEY_LAST_SHOWN_DAY)

    override fun setLastShownDay(day: String) = settings.putString(KEY_LAST_SHOWN_DAY, day)
}
