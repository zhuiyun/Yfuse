package com.yfuse.feature.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class NativeBluRayOverlayFrame(
    val width: Int,
    val height: Int,
    /** Straight ARGB pixels in authored overlay-plane coordinates. */
    val argb: IntArray,
)

/** Active YCore libbluray menu state shared with the player overlay; no playback engine is created. */
internal object NativeRemoteBluRaySessionRegistry {
    private val mutableActiveSession = MutableStateFlow<HdmvDiscSession?>(null)
    val activeSession: StateFlow<HdmvDiscSession?> = mutableActiveSession.asStateFlow()

    private val mutableOverlay = MutableStateFlow<NativeBluRayOverlayFrame?>(null)
    val overlay: StateFlow<NativeBluRayOverlayFrame?> = mutableOverlay.asStateFlow()

    fun activate(session: HdmvDiscSession) {
        if (mutableActiveSession.value !== session) {
            mutableOverlay.value = null
            mutableActiveSession.value = session
        }
    }

    fun deactivate(session: HdmvDiscSession) {
        if (mutableActiveSession.value === session) {
            mutableOverlay.value = null
            mutableActiveSession.value = null
        }
    }

    fun updateOverlay(
        session: HdmvDiscSession,
        frame: NativeBluRayOverlayFrame?,
    ) {
        if (mutableActiveSession.value === session) mutableOverlay.value = frame
    }
}
