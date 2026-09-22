package com.yfuse.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.yfuse.R
import com.yfuse.feature.profile.AppIconPreview
import com.yfuse.feature.profile.AppIconVariant
import com.yfuse.feature.profile.currentAppIconVariant

@Composable
actual fun CloudPlayerLogo(modifier: Modifier) {
    val variant = currentAppIconVariant()
    if (variant == AppIconVariant.AuroraDark || variant == AppIconVariant.AuroraLight) {
        AppIconPreview(variant, modifier)
        return
    }
    Image(
        painter =
            painterResource(
                if (variant == AppIconVariant.CloudPlayer) R.drawable.cloud_player_logo else R.drawable.yfuse_mark,
            ),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
