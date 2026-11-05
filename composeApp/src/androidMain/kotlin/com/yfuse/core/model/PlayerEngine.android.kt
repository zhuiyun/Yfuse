package com.yfuse.core.model

import com.yfuse.core.platform.AppBuildConfig as BuildConfig

actual fun bundledPlayerEngines(): Set<PlayerEngine> =
    buildSet {
        add(PlayerEngine.Exo)
        add(PlayerEngine.Mpv)
        if (com.yfuse.senplayer.bridge.BuildConfig.RUNTIME_INCLUDED &&
            android.os.Build.VERSION.SDK_INT >= 28 &&
            android.os.Process.is64Bit() &&
            "arm64-v8a" in android.os.Build.SUPPORTED_ABIS
        ) {
            add(PlayerEngine.SenPlayer)
        }
        if (BuildConfig.YFUSE_MDK_INCLUDED) add(PlayerEngine.Mdk)
    }
