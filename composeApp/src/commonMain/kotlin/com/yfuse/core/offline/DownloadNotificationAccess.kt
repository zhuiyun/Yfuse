package com.yfuse.core.offline

import androidx.compose.runtime.Composable

data class DownloadNotificationAccess(
    val label: String,
    val openSettings: () -> Unit,
)

/** Re-read when returning from system settings, where notification access can change. */
@Composable
expect fun rememberDownloadNotificationAccess(): DownloadNotificationAccess
