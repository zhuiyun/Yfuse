package com.yfuse.core.model

import com.yfuse.BuildConfig

actual fun bundledPlayerEngines(): Set<PlayerEngine> =
    buildSet {
        add(PlayerEngine.Exo)
        add(PlayerEngine.Mpv)
        if (BuildConfig.YFUSE_MDK_INCLUDED) add(PlayerEngine.Mdk)
    }
