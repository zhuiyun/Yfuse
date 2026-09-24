package com.yfuse.core.util

/**
 * Whether the calling thread is the platform's UI thread.
 *
 * False where there is none - JVM unit tests run against a stubbed framework - so work that moves
 * itself off the UI thread stays on a test's virtual clock there.
 */
internal expect fun isUiThread(): Boolean
