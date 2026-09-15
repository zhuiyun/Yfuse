package com.yfuse.app

import androidx.compose.runtime.Composable

/** Connects foreground sync, profile boundaries and explicit device handoff on each platform. */
@Composable
expect fun BindProductServices(root: RootComponent)
