package com.yfuse.feature.profile

import androidx.compose.runtime.Composable

/**
 * Opens the system picker and hands back a URI the app may still read after a restart.
 *
 * A background is chosen once and expected to survive every launch after that, which rules
 * out the ordinary photo picker: it grants access for the lifetime of the process. The
 * platform implementation takes a persistable grant, and passes null when the user backs out.
 */
@Composable
expect fun rememberBackgroundImagePicker(onPicked: (String?) -> Unit): () -> Unit

/**
 * Drops a persistable grant taken earlier.
 *
 * Clearing the preference alone would leave this app holding read access to one of the
 * user's photos indefinitely, for a picture it no longer displays.
 */
expect fun releaseBackgroundImage(uri: String)
