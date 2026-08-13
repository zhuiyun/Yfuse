package com.yfuse.feature.profile

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.popTo
import com.arkivanov.decompose.value.Value
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.app.AppDependencies
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import kotlinx.serialization.Serializable

/**
 * The "我的" tab. Server management has moved back out into its own 服务器 tab — see
 * [com.yfuse.feature.servers.ServersTabComponent] — so what is left here is the account,
 * the settings and the downloads.
 */
class ProfileTabComponent(
    componentContext: ComponentContext,
    private val storeFactory: StoreFactory,
    private val registry: ServerRegistry,
    val themePreferences: ThemePreferences,
    /** Re-opens the player on the current 一起看 room; see `RootComponent.enterWatchRoom`. */
    private val onEnterWatchRoom: () -> Unit,
    /** Switches to the 服务器 tab, which owns the list this page used to embed. */
    private val onOpenServers: () -> Unit,
    private val dependencies: AppDependencies,
) : ComponentContext by componentContext {
    private val navigation = StackNavigation<Config>()

    val stack: Value<ChildStack<Config, Child>> =
        childStack(
            source = navigation,
            serializer = Config.serializer(),
            initialConfiguration = Config.Home,
            // The Compose shell owns system back so only the visible tab can pop.
            handleBackButton = false,
            childFactory = ::child,
        )

    @Serializable
    sealed interface Config {
        @Serializable data object Home : Config
    }

    sealed interface Child {
        class Home(
            val component: ProfileComponent,
        ) : Child
    }

    fun navigateBack() {
        navigation.pop()
    }

    /**
     * Back to this tab's own root in one step — what tapping the current tab means.
     *
     * Popping one level at a time would land the user somewhere in the middle of the
     * stack they were trying to leave.
     */
    fun popToRoot() {
        navigation.popTo(index = 0)
    }

    private fun child(
        config: Config,
        context: ComponentContext,
    ): Child =
        when (config) {
            Config.Home ->
                Child.Home(
                    ProfileComponent(
                        componentContext = context,
                        storeFactory = storeFactory,
                        registry = registry,
                        themePreferences = themePreferences,
                        onEnterWatchRoom = onEnterWatchRoom,
                        onOpenServers = onOpenServers,
                        dependencies = dependencies,
                    ),
                )
        }
}
