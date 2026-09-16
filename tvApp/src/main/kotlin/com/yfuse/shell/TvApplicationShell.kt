package com.yfuse.shell

import android.content.Context
import com.yfuse.BuildConfig
import com.yfuse.core.platform.AppBuildConfig
import com.yfuse.core.platform.AppBuildValues

class TvApplicationShell : com.yfuse.tv.TvApplication() {
    override fun attachBaseContext(base: Context) {
        AppBuildConfig.install(
            AppBuildValues(
                DEBUG = BuildConfig.DEBUG,
                APPLICATION_ID = BuildConfig.APPLICATION_ID,
                VERSION_NAME = BuildConfig.VERSION_NAME,
                VERSION_CODE = BuildConfig.VERSION_CODE,
                BUILD_REVISION = BuildConfig.BUILD_REVISION,
                TMDB_TOKEN = BuildConfig.TMDB_TOKEN,
                UPDATE_MANIFEST_PUBLIC_KEY = BuildConfig.UPDATE_MANIFEST_PUBLIC_KEY,
                YFUSE_MDK_INCLUDED = BuildConfig.YFUSE_MDK_INCLUDED,
                YFUSE_NATIVE_ONLY_RUNTIME = BuildConfig.YFUSE_NATIVE_ONLY_RUNTIME,
                YFUSE_YCORE_GPU_INCLUDED = BuildConfig.YFUSE_YCORE_GPU_INCLUDED,
                YFUSE_CAST_RECEIVER_APPLICATION_ID = BuildConfig.YFUSE_CAST_RECEIVER_APPLICATION_ID,
                YFUSE_PACKAGE_PROFILE = BuildConfig.YFUSE_PACKAGE_PROFILE,
            ),
        )
        super.attachBaseContext(base)
    }
}
