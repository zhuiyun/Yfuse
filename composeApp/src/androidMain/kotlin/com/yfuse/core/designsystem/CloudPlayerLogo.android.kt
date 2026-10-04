package com.yfuse.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.yfuse.feature.profile.AppIconPreview
import com.yfuse.feature.profile.AppIconVariant
import com.yfuse.feature.profile.currentAppIconVariant
import com.yfuse.shared.R

@Composable
actual fun CloudPlayerLogo(modifier: Modifier) {
    val variant = currentAppIconVariant()
    val mark =
        when (variant) {
            AppIconVariant.Default, AppIconVariant.Graphite -> R.drawable.yfuse_mark
            AppIconVariant.CloudPlayer -> R.drawable.cloud_player_logo
            // Aurora and the vector icons: each mark is made for its own ground, so the whole tile.
            else -> null
        }
    if (mark == null) {
        AppIconPreview(variant, modifier)
        return
    }
    Image(
        painter = painterResource(mark),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
