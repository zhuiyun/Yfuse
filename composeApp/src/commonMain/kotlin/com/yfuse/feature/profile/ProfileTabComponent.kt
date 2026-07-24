package com.yfuse.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DelicateDecomposeApi
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.value.Value
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import com.yfuse.feature.servers.ServersComponent
import kotlinx.serialization.Serializable

/**
 * The "我的" tab. Server management lives here now rather than as its own tab.
 */
@OptIn(DelicateDecomposeApi::class)
class ProfileTabComponent(
    componentContext: ComponentContext,
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    val themePreferences: ThemePreferences,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    val stack: Value<ChildStack<Config, Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = Config.Home,
        handleBackButton = true,
        childFactory = ::child,
    )

    @Serializable
    sealed interface Config {
        @Serializable data object Home : Config
        @Serializable data object Servers : Config
    }

    sealed interface Child {
        class Home(val component: ProfileComponent) : Child
        class Servers(val component: ServersComponent) : Child
    }

    fun navigateBack() {
        navigation.pop()
    }

    private fun child(config: Config, context: ComponentContext): Child = when (config) {
        Config.Home -> Child.Home(
            ProfileComponent(
                componentContext = context,
                storeFactory = storeFactory,
                registry = registry,
                themePreferences = themePreferences,
                onOpenServers = { navigation.push(Config.Servers) },
            ),
        )
        Config.Servers -> Child.Servers(
            ServersComponent(
                componentContext = context,
                storeFactory = storeFactory,
                repo = repo,
                registry = registry,
                onServerAdded = { navigation.pop() },
                onBack = { navigation.pop() },
            ),
        )
    }
}
