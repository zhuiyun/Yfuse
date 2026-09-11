package com.yfuse.feature.player

/** Routing/controls subscribe to state transitions; clocks and telemetry keep their own live source. */
internal fun PlaybackState.runtimeProjection(): PlaybackState =
    copy(
        positionMs = if (positionMs > 0L) 1L else 0L,
        bufferedPositionMs = 0L,
        diagnostics =
            diagnostics.copy(
                playbackHealth = "",
                powerProfile = "",
                resourcePressure = "",
                performanceBaseline = "",
                bitrateBitsPerSecond = 0L,
                frameRate = 0f,
                droppedFrames = 0,
                avSyncOffsetMs = null,
                avSyncMeasurement = "",
                bufferedDurationMs = 0L,
                bufferEvents = 0,
                rebufferDurationMs = 0L,
                longestRebufferMs = 0L,
                networkBitsPerSecond = 0L,
                sourceQueueBytes = 0L,
                sourceBufferedMs = 0L,
                sourceStarvationCount = 0L,
                outputEvidence =
                    diagnostics.outputEvidence.copy(
                        audioUnderrunCount = 0,
                        mistimedFrameCount = 0,
                        rendererDetail = "",
                    ),
            ),
    )
