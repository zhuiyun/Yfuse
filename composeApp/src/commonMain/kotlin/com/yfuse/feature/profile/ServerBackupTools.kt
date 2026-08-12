package com.yfuse.feature.profile

import androidx.compose.runtime.Composable

@Composable
expect fun ServerBackupTools(
    serverCount: Int,
    onExport: (CharArray, Long) -> Result<String>,
    onImport: (String, CharArray, Long) -> Result<Int>,
)
