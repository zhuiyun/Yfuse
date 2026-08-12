package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app mark, supplied by the platform so common screens reuse the launcher artwork
 * rather than a second drawing of it.
 *
 * It is the mark alone on transparency — no plate. The launcher supplies the white tile
 * from the adaptive icon's background layer; in the app the mark sits on whatever is
 * behind it, which is the whole reason it is a shape and not a tile.
 */
@Composable
expect fun CloudPlayerLogo(modifier: Modifier = Modifier)
