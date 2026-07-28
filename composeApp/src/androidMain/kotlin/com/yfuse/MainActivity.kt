package com.yfuse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.retainedComponent
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.app.AnimatedSplashApp
import com.yfuse.app.RootComponent
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.update.AppUpdateManager
import com.yfuse.update.AppUpdateOverlay
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {
    private lateinit var updateManager: AppUpdateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val koin = GlobalContext.get()
        val root = retainedComponent { ctx ->
            RootComponent(
                componentContext = ctx,
                storeFactory = koin.get<StoreFactory>(),
                repo = koin.get<EmbyRepository>(),
                tmdb = koin.get<TmdbRepository>(),
                registry = koin.get<ServerRegistry>(),
                themePreferences = koin.get<ThemePreferences>(),
                searchHistory = koin.get<SearchHistory>(),
                syncManager = koin.get<ServerSyncManager>(),
            )
        }

        updateManager = AppUpdateManager(this)
        setContent {
            Box(Modifier.fillMaxSize()) {
                AnimatedSplashApp(root)
                AppUpdateOverlay(updateManager)
            }
        }
        updateManager.check()
    }

    override fun onResume() {
        super.onResume()
        if (::updateManager.isInitialized) updateManager.resumeInstall()
    }
}
