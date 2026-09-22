package com.yfuse.feature.player

import android.content.Context
import java.security.MessageDigest

/** Stable device/product profile plus output category fallback; never stores the raw device name. */
internal class AudioOutputDelayPreferences(
    context: Context,
) {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            "audio_output_delay",
            Context.MODE_PRIVATE,
        )

    fun read(route: String): Long? {
        val key = audioOutputDelayProfileKey(route) ?: return null
        val category = audioOutputDelayCategoryKey(route)
        return when {
            preferences.contains(key) -> preferences.getLong(key, 0L)
            preferences.contains(category) -> preferences.getLong(category, 0L)
            else -> null
        }?.coerceIn(-5000L, 5000L)
    }

    fun write(
        route: String,
        value: Long,
    ) {
        val key = audioOutputDelayProfileKey(route) ?: return
        val delay = value.coerceIn(-5000L, 5000L)
        preferences
            .edit()
            .putLong(key, delay)
            .putLong(audioOutputDelayCategoryKey(route), delay)
            .apply()
    }
}

internal fun audioOutputDelayProfileKey(route: String): String? =
    route.trim().takeIf(String::isNotEmpty)?.let { "device_${audioRouteDigest(it)}" }

internal fun audioOutputDelayCategoryKey(route: String): String =
    "category_${audioRouteDigest(route.substringBefore('·').trim())}"

private fun audioRouteDigest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
