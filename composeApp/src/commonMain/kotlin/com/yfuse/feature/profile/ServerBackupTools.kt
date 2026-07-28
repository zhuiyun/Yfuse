package com.yfuse.feature.profile

import androidx.compose.runtime.Composable

@Composable
expect fun ServerBackupTools(
    payload: String,
    serverCount: Int,
    onImport: (String) -> Result<Int>,
)
