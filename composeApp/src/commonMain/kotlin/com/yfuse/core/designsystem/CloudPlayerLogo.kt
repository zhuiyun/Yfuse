package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app mark, supplied by the platform so common screens reuse the launcher artwork
 * rather than a second drawing of it.
 *
 * Classic marks retain transparency. Aurora and the vector icons are drawn as the whole tile,
 * ground included, so the in-app mark matches the selected launcher artwork: each of those marks
 * is made for its own ground.
 */
@Composable
expect fun CloudPlayerLogo(modifier: Modifier = Modifier)
