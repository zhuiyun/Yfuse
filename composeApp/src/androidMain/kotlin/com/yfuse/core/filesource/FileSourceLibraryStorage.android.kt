package com.yfuse.core.filesource

import com.yfuse.core.util.androidAppContext

actual fun createFileSourceLibraryStorage(): FileSourceLibraryStorage =
    FileSourceLibraryFileStorage {
        androidAppContext?.filesDir
    }
