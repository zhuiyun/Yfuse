package com.yfuse.core.util

import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** A main-thread coroutine scope tied to a Decompose component's [lifecycle]. */
fun componentScope(lifecycle: Lifecycle): CoroutineScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lifecycle.doOnDestroy(scope::cancel)
    return scope
}
