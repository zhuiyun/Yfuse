package com.yfuse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.arkivanov.decompose.retainedComponent
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.app.App
import com.yfuse.app.RootComponent
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SessionManager
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val koin = GlobalContext.get()
        val root = retainedComponent { ctx ->
            RootComponent(
                componentContext = ctx,
                storeFactory = koin.get<StoreFactory>(),
                repo = koin.get<EmbyRepository>(),
                session = koin.get<SessionManager>(),
            )
        }

        setContent { App(root) }
    }
}
