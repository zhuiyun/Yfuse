package com.yfuse

import android.os.Build
import java.util.UUID

actual fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

// In-memory stable id for the process lifetime. Persisted device id can be
// added in a later phase (stored via SessionManager).
private val stableDeviceId: String by lazy { UUID.randomUUID().toString() }

actual fun deviceId(): String = stableDeviceId
