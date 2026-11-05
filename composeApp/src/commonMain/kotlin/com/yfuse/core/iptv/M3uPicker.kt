package com.yfuse.core.iptv

import androidx.compose.runtime.Composable

@Composable
expect fun rememberM3uPicker(onResult: (Result<String>) -> Unit): () -> Unit
