package com.yfuse.core.model

actual fun bundledPlayerEngines(): Set<PlayerEngine> =
    buildSet {
        add(PlayerEngine.YCore)
        if (com.yfuse.senplayer.SenPlayerSession
                .supportsProcess()
        ) {
            add(PlayerEngine.SenPlayer)
        }
    }
