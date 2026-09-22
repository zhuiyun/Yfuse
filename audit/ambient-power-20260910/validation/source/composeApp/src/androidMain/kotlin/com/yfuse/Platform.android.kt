package com.yfuse

import android.content.SharedPreferences
import android.os.Build
import java.util.UUID

actual fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

private const val DEVICE_ID_KEY = "emby.deviceId"

@Volatile
private var persistedDeviceId: String? = null

/**
 * Covers the window before [initializeDeviceId] runs, and unit tests that never call it.
 * Deliberately not written anywhere: an id that was never persisted must not become the
 * persisted one on the next launch.
 */
private val ephemeralDeviceId: String by lazy { UUID.randomUUID().toString() }

/**
 * Binds this install's Emby device identity to storage. Call once, before any Emby request.
 *
 * This used to be a fresh `UUID.randomUUID()` per process. Emby keys a session — and the
 * transcoding job behind it — on the device id, so a new id every launch meant the server
 * accumulated one phantom device per app start and could never match a `Playing/Stopped`
 * report back to the encoding it was meant to end. See [com.yfuse.core.network.EmbyStream].
 */
fun initializeDeviceId(preferences: SharedPreferences) {
    if (persistedDeviceId != null) return
    persistedDeviceId = preferences.getString(DEVICE_ID_KEY, null)
        ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(DEVICE_ID_KEY, it).apply()
        }
}

actual fun deviceId(): String = persistedDeviceId ?: ephemeralDeviceId
