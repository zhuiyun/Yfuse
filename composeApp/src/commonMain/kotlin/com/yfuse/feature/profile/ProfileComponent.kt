package com.yfuse.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.util.clearImageCache

class ProfileComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    private val registry: ServerRegistry,
    val themePreferences: ThemePreferences,
    val onOpenServers: () -> Unit,
) : ComponentContext by componentContext {

    val store = ProfileStoreFactory(storeFactory, registry).create()

    /** 下载与缓存 · 清除全部缓存. */
    fun onClearCache() = clearImageCache()

    /** Long-pressing a non-current server row removes it. */
    fun onRemoveServer(id: String) = registry.remove(id)

    init {
        lifecycle.doOnDestroy(store::dispose)
    }
}
