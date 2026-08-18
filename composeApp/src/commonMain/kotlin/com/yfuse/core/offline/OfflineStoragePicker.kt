package com.yfuse.core.offline

import androidx.compose.runtime.Composable

/** Opens the platform directory picker and returns a persistable tree URI plus its label. */
@Composable
expect fun rememberOfflineStorageDirectoryPicker(onSelected: (treeUri: String, label: String) -> Unit): () -> Unit
