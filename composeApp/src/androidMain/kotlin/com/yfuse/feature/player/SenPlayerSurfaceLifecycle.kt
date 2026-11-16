package com.yfuse.feature.player

import java.io.IOException

/** Some original runtimes reject a null output. Retire that session before recreating its surface. */
internal fun detachSenPlayerSurface(
    pause: () -> Unit,
    detach: () -> Unit,
    close: () -> Unit,
): Boolean {
    pause()
    try {
        detach()
        return false
    } catch (error: IOException) {
        if (error.message != "SenPlayer command failed (-6)") throw error
        close()
        return true
    }
}
