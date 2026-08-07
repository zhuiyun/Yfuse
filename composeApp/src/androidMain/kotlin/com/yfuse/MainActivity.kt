package com.yfuse

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import com.arkivanov.decompose.retainedComponent
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.russhwolf.settings.Settings
import com.yfuse.app.AnimatedSplashApp
import com.yfuse.app.RootComponent
import com.yfuse.app.isNightMode
import com.yfuse.app.splashBackground
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.designsystem.resolveDark
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.WatchInvite
import com.yfuse.update.AppUpdateManager
import com.yfuse.update.AppUpdateOverlay
import com.yfuse.update.LocalAppUpdateManager
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {
    private lateinit var updateManager: AppUpdateManager
    private var rootComponent: RootComponent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The starting window was painted from the -night resources, so it followed the OS
        // rather than our own light/dark setting. Repaint the activity window in the colour we
        // actually resolved, so nothing behind the Compose splash can flash the other way;
        // AnimatedSplashApp eases the visible splash across the same gap.
        val koin = GlobalContext.get()
        val dark = koin.get<ThemePreferences>().mode.value.resolveDark(resources.isNightMode())
        window.setBackgroundDrawable(ColorDrawable(splashBackground(dark).toArgb()))

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

        rootComponent = root

        updateManager = AppUpdateManager(this, koin.get<Settings>())
        setContent {
            CompositionLocalProvider(LocalAppUpdateManager provides updateManager) {
                AnimatedSplashApp(root) {
                    AppUpdateOverlay(updateManager)
                }
            }
        }
        updateManager.checkIfDue()

        // A cold start from a shared link arrives here rather than in onNewIntent.
        consumeInviteIntent(intent)
    }

    /**
     * The activity is `singleTask`, so a link tapped while Yfuse is already running is
     * delivered here instead of creating a second instance — including while the player is
     * in the foreground on top of us.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeInviteIntent(intent)
    }

    private fun consumeInviteIntent(intent: Intent?) {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return
        val invite = WatchInvite.parse(data.toString()) ?: return
        rootComponent?.offerInvite(invite)
        // Clear the payload so a configuration change or a later resume doesn't re-offer an
        // invite the user already dealt with.
        intent.data = null
    }

    override fun onResume() {
        super.onResume()
        if (::updateManager.isInitialized) updateManager.resumeInstall()
    }
}
