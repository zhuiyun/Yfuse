package com.yfuse.core.network

import com.yfuse.core.platform.AppBuildConfig as BuildConfig

actual fun tmdbToken(): String = BuildConfig.TMDB_TOKEN
