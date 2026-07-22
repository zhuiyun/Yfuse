package com.yfuse.feature.home

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository

class HomeComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
) : ComponentContext by componentContext {

    val store = HomeStoreFactory(storeFactory, repo).create()

    init {
        store.accept(HomeIntent.Load)
        lifecycle.doOnDestroy(store::dispose)
    }
}
