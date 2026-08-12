package com.yfuse.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.yfuse.R

@Composable
actual fun CloudPlayerLogo(modifier: Modifier) {
    Image(
        painter = painterResource(R.drawable.yfuse_mark),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
