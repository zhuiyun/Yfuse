package com.yfuse.feature.profile

import androidx.compose.runtime.Composable
import com.yfuse.core.security.RelayMigrationDescriptor
import com.yfuse.core.security.RelayMigrationPackage

@Composable
expect fun ServerBackupTools(
    serverCount: Int,
    onExport: (CharArray, Long) -> Result<String>,
    onImport: (String, CharArray, Long) -> Result<Int>,
    onExportRelay: (Long) -> Result<RelayMigrationPackage>,
    onInspectRelay: (String) -> RelayMigrationDescriptor,
    onIsRelay: (String) -> Boolean,
    onImportRelay: (String, ByteArray, Long) -> Result<Int>,
)
