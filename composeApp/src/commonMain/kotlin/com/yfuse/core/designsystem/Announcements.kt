package com.yfuse.core.designsystem

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/**
 * Marks a status line — an error, a network notice, 「已续播」 — as a live region, so a screen
 * reader says it when it appears or changes instead of leaving it to be found by touch.
 *
 * [assertive] interrupts whatever is being read; keep it for a failure that stops what the
 * person was doing. Put this on the node holding stable wording, never on a counter that
 * ticks every second — each tick would be read out.
 */
fun Modifier.liveStatus(assertive: Boolean = false): Modifier =
    semantics { liveRegion = if (assertive) LiveRegionMode.Assertive else LiveRegionMode.Polite }
