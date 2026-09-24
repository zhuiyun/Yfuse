package com.yfuse.core.util

import android.os.Looper

// The stubbed framework of a host unit test has no main looper, and so no UI thread.
internal actual fun isUiThread(): Boolean = Looper.getMainLooper()?.isCurrentThread == true
