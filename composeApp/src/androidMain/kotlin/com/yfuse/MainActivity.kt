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
import com.yfuse.app.AnimatedSplashApp
import com.yfuse.app.AppDependencies
import com.yfuse.app.RootComponent
import com.yfuse.app.isNightMode
import com.yfuse.app.launchWindowDarkMode
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
import com.yfuse.feature.player.PlaybackSourcePreloader
import org.koin.core.context.GlobalContext

class MainActivity : ComponentActivity() {
    private lateinit var updateManager: AppUpdateManager
    private var rootComponent: RootComponent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep the window on exactly the colour the first Compose frame will draw. With the
        // animated splash enabled that frame deliberately starts from the system resource theme
        // and eases towards the app theme. Repainting the window to the app theme here used to
        // produce a system -> app -> system -> app flash when those themes differed.
        val koin = GlobalContext.get()
        val themePreferences = koin.get<ThemePreferences>()
        val systemDark = resources.isNightMode()
        val appDark = themePreferences.mode.value.resolveDark(systemDark)
        val windowDark = launchWindowDarkMode(
            splashEnabled = themePreferences.splashAnimation.value,
            systemDark = systemDark,
            appDark = appDark,
        )
        window.setBackgroundDrawable(ColorDrawable(splashBackground(windowDark).toArgb()))

        val root = retainedComponent { ctx ->
            RootComponent(
                componentContext = ctx,
                storeFactory = koin.get<StoreFactory>(),
                repo = koin.get<EmbyRepository>(),
                tmdb = koin.get<TmdbRepository>(),
                registry = koin.get<ServerRegistry>(),
                themePreferences = themePreferences,
                searchHistory = koin.get<SearchHistory>(),
                syncManager = koin.get<ServerSyncManager>(),
                dependencies = AppDependencies(
                    calendarRepository = koin.get(),
                    tmdbHomeCache = koin.get(),
                    offlineMediaManager = koin.get(),
                    playbackTrackRequest = koin.get(),
                    serverSyncManager = koin.get(),
                    watchTogether = koin.get(),
                    watchTogetherPreferences = koin.get(),
                    inviteResolver = koin.get(),
                    playbackSourcePreloader = runCatching { koin.get<PlaybackSourcePreloader>() }.getOrNull(),
                    playbackRecovery = koin.get(),
                    playbackReportingCoordinator = koin.get(),
                    playbackPreferences = koin.get(),
                    playbackFailoverRequest = koin.get(),
                    userAgentPreferences = koin.get(),
                    danmakuPreferences = koin.get(),
                    skipSegmentPreferences = koin.get(),
                    libraryCache = koin.get(),
                    lanDiscovery = koin.get(),
                    account = koin.get(),
                    serverHealthMonitor = koin.get(),
                    serverActivity = koin.get(),
                    serverStats = koin.get(),
                    serverRegistry = koin.get(),
                ),
            )
        }

        rootComponent = root

        // Application-scoped: a download started here has to survive this activity, so the
        // update check that starts one is triggered from the UI (see AppUpdateOverlay) rather
        // than from onCreate.
        updateManager = koin.get<AppUpdateManager>()
        setContent {
            CompositionLocalProvider(LocalAppUpdateManager provides updateManager) {
                AnimatedSplashApp(root) {
                    AppUpdateOverlay(updateManager, root)
                }
            }
        }

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
