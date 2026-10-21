package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class NativeSubtitleRegion(
    val useBlackBar: Boolean,
    val margin: Int,
)

@Composable
internal fun nativeSubtitleRegion(
    container: IntSize,
    picture: IntSize,
    controls: SubtitleControlState,
): NativeSubtitleRegion {
    val density = LocalDensity.current
    val inset = with(density) { 8.dp.roundToPx() }
    val reserve =
        nativeSubtitleReserve(
            picture.height,
            controls.scale,
            controls.secondaryScale.takeIf { controls.secondaryTrackId != null },
            with(density) { 28.sp.roundToPx() },
        )
    val placement =
        placeSubtitleBlock(container.height, picture.height, reserve, controls.region, controls.position, inset)
    return NativeSubtitleRegion(placement.inBlackBar, inset)
}
