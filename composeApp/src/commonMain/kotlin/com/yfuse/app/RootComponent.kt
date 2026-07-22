package com.yfuse.app

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.value.Value
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SessionManager
import com.yfuse.feature.home.HomeComponent
import com.yfuse.feature.login.LoginComponent
import com.yfuse.feature.server.ServerComponent
import kotlinx.serialization.Serializable

/**
 * Owns the navigation stack. Starts at Home when a session already exists,
 * otherwise walks the user through Server -> Login -> Home.
 */
class RootComponent(
    componentContext: ComponentContext,
    private val storeFactory: StoreFactory,
    private val repo: EmbyRepository,
    private val session: SessionManager,
) : ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    val childStack: Value<ChildStack<Config, Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        initialConfiguration = if (session.hasSession()) Config.Home else Config.Server,
        handleBackButton = true,
        childFactory = ::createChild,
    )

    sealed interface Child {
        class Server(val component: ServerComponent) : Child
        class Login(val component: LoginComponent) : Child
        class Home(val component: HomeComponent) : Child
    }

    @Serializable
    sealed interface Config {
        @Serializable data object Server : Config
        @Serializable data class Login(val baseUrl: String) : Config
        @Serializable data object Home : Config
    }

    private fun createChild(config: Config, context: ComponentContext): Child = when (config) {
        Config.Server -> Child.Server(
            ServerComponent(context, storeFactory, repo) { baseUrl ->
                navigation.push(Config.Login(baseUrl))
            },
        )
        is Config.Login -> Child.Login(
            LoginComponent(context, storeFactory, repo, config.baseUrl) {
                navigation.replaceAll(Config.Home)
            },
        )
        Config.Home -> Child.Home(
            HomeComponent(context, storeFactory, repo),
        )
    }
}
