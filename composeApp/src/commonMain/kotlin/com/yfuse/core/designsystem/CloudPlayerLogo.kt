package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app mark, supplied by the platform so common screens reuse the launcher artwork
 * rather than a second drawing of it.
 *
 * Classic marks retain transparency. Aurora variants retain the supplied light or dark
 * ground so the in-app mark matches the selected launcher artwork.
 */
@Composable
expect fun CloudPlayerLogo(modifier: Modifier = Modifier)
