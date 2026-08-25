package com.yfuse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import com.arkivanov.decompose.retainedComponent
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yfuse.app.AnimatedSplashApp
import com.yfuse.app.AppDependencies
import com.yfuse.app.RootComponent
import com.yfuse.app.isNightMode
import com.yfuse.app.launchWindowDarkMode
import com.yfuse.app.splashBackground
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.SearchHistory
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.designsystem.resolveDark
import com.yfuse.core.logging.AppLog
import com.yfuse.core.performance.AppJankMonitor
import com.yfuse.core.performance.preferHighRefreshRateForUi
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.WatchInvite
import com.yfuse.feature.calendar.scheduleCalendarReminderWork
import com.yfuse.feature.player.PlaybackSourcePreloader
import com.yfuse.feature.profile.applyPendingAppIconVariant
import com.yfuse.update.AppUpdateManager
import com.yfuse.update.AppUpdateOverlay
import com.yfuse.update.LocalAppUpdateManager
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var updateManager: AppUpdateManager
    private lateinit var serverHealthMonitor: ServerHealthMonitor
    private lateinit var serverSyncManager: ServerSyncManager
    private var rootComponent: RootComponent? = null
    private var jankMonitor: AppJankMonitor? = null
    private var calendarNotificationPermissionRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferHighRefreshRateForUi()
        enableEdgeToEdge()

        // Keep the window on exactly the colour the first Compose frame will draw. With the
        // animated splash enabled that frame deliberately starts from the system resource theme
        // and eases towards the app theme. Repainting the window to the app theme here used to
        // produce a system -> app -> system -> app flash when those themes differed.
        val koin = GlobalContext.get()
        serverHealthMonitor = koin.get()
        serverSyncManager = koin.get()
        val themePreferences = koin.get<ThemePreferences>()
        val systemDark = resources.isNightMode()
        val appDark = themePreferences.mode.value.resolveDark(systemDark)
        val windowDark =
            launchWindowDarkMode(
                splashEnabled = themePreferences.splashAnimation.value,
                systemDark = systemDark,
                appDark = appDark,
            )
        window.setBackgroundDrawable(ColorDrawable(splashBackground(windowDark).toArgb()))

        val root =
            retainedComponent { ctx ->
                RootComponent(
                    componentContext = ctx,
                    storeFactory = koin.get<StoreFactory>(),
                    repo = koin.get<EmbyRepository>(),
                    tmdb = koin.get<TmdbRepository>(),
                    registry = koin.get<ServerRegistry>(),
                    themePreferences = themePreferences,
                    searchHistory = koin.get<SearchHistory>(),
                    syncManager = koin.get<ServerSyncManager>(),
                    dependencies =
                        AppDependencies(
                            calendarRepository = koin.get(),
                            calendarIdentityResolver = koin.get(),
                            calendarFollowStore = koin.get(),
                            tmdbHomeCache = koin.get(),
                            tgtoMedia = koin.get(),
                            tgtoMediaPreferences = koin.get(),
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
        observeCalendarNotificationPermission(koin.get())

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
        jankMonitor =
            runCatching { AppJankMonitor(window) }
                .onFailure { error ->
                    AppLog.warning(
                        category = "performance.ui",
                        event = "jank_monitor_unavailable",
                        message = "JankStats could not attach to the main window",
                        throwable = error,
                    )
                }.getOrNull()

        // A cold start from a shared link arrives here rather than in onNewIntent.
        consumeInviteIntent(intent)
        consumeCalendarIntent(intent)
    }

    private fun observeCalendarNotificationPermission(follows: CalendarFollowStore) {
        if (Build.VERSION.SDK_INT < 33) return
        lifecycleScope.launch {
            follows.followed
                .map { followed ->
                    followed
                        .filter { it.reminderMode != CalendarReminderMode.Off }
                        .map { listOf(it.tmdbId.toString(), it.reminderMode.name, it.remindBeforeMinutes.toString()) }
                }.distinctUntilChanged()
                .collect { reminderConfiguration ->
                    scheduleCalendarReminderWork(this@MainActivity)
                    if (
                        reminderConfiguration.isNotEmpty() &&
                        !calendarNotificationPermissionRequested &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        calendarNotificationPermissionRequested = true
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), CALENDAR_NOTIFICATION_PERMISSION_REQUEST)
                    }
                }
        }
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
        consumeCalendarIntent(intent)
    }

    private companion object {
        const val EXTRA_CALENDAR_SERVER_ID = "calendar_server_id"
        const val EXTRA_CALENDAR_SERIES_ITEM_ID = "calendar_series_item_id"
        const val CALENDAR_NOTIFICATION_PERMISSION_REQUEST = 4103
    }

    private fun consumeCalendarIntent(intent: Intent?) {
        val itemId =
            intent?.getStringExtra(EXTRA_CALENDAR_SERIES_ITEM_ID)
                ?.takeIf(String::isNotBlank)
                ?: return
        rootComponent?.openCalendarTarget(
            serverId = intent.getStringExtra(EXTRA_CALENDAR_SERVER_ID),
            itemId = itemId,
        )
        intent.removeExtra(EXTRA_CALENDAR_SERVER_ID)
        intent.removeExtra(EXTRA_CALENDAR_SERIES_ITEM_ID)
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
        preferHighRefreshRateForUi()
        if (::updateManager.isInitialized) updateManager.resumeInstall()
    }

    override fun onStart() {
        super.onStart()
        jankMonitor?.start()
        serverHealthMonitor.setAppForeground(true)
        serverSyncManager.setAppForeground(true)
    }

    /**
     * Launcher-icon switches are applied here rather than where they are chosen.
     *
     * Enabling one LAUNCHER component and disabling the others takes this activity's own
     * component with it, and Android answers that by removing the task — so doing it on the
     * tap closed the app on a user who was still in settings. Backgrounded is the moment it
     * costs nothing; see [com.yfuse.feature.profile.applyPendingAppIconVariant].
     */
    override fun onStop() {
        jankMonitor?.stop()
        serverHealthMonitor.setAppForeground(false)
        serverSyncManager.setAppForeground(false)
        super.onStop()
        applyPendingAppIconVariant()
    }
}
