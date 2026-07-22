package com.yfuse.feature.login

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.util.componentScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class LoginComponent(
    componentContext: ComponentContext,
    storeFactory: StoreFactory,
    repo: EmbyRepository,
    baseUrl: String,
    private val onLoggedIn: () -> Unit,
) : ComponentContext by componentContext {

    val store = LoginStoreFactory(storeFactory, repo, baseUrl).create()

    init {
        val scope = componentScope(lifecycle)
        store.labels
            .onEach { if (it is LoginLabel.NavigateHome) onLoggedIn() }
            .launchIn(scope)
        lifecycle.doOnDestroy(store::dispose)
    }
}
