package com.yfuse.update

import android.content.pm.PackageInfo
import android.os.Build

/** Keep Android 9's high version bits; truncation could make a different archive match the feed. */
internal val PackageInfo.updateVersionCode: Long
    get() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
