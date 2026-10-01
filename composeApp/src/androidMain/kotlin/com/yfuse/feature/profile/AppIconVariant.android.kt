package com.yfuse.feature.profile

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yfuse.APP_ENTRY_ALIAS
import com.yfuse.core.logging.AppLog
import com.yfuse.core.util.androidAppContext
import com.yfuse.shortcuts.scheduleShortcutUpdate
import java.util.Collections
import java.util.WeakHashMap

/**
 * The manifest component each variant corresponds to.
 *
 * [AppIconVariant.Default] is `MainActivity` itself rather than a fourth alias, so a fresh
 * install with no preference ever set is in exactly the state it shipped in.
 */
private fun AppIconVariant.componentClass(): String =
    when (this) {
        AppIconVariant.Default -> "com.yfuse.MainActivity"
        AppIconVariant.Graphite -> "com.yfuse.LauncherGraphite"
        AppIconVariant.CloudPlayer -> "com.yfuse.LauncherCloud"
        AppIconVariant.AuroraDark -> "com.yfuse.LauncherAuroraDark"
        AppIconVariant.AuroraLight -> "com.yfuse.LauncherAuroraLight"
    }

/**
 * A switch that has been chosen but not yet handed to the package manager.
 *
 * Disabling the component of the activity the user is standing in tears down its task —
 * `DONT_KILL_APP` keeps the *process*, not the task — so applying the choice on the tap
 * dropped the user on their home screen mid-settings.
 *
 * Held in memory only. It is applied once the user has left the app — see
 * [watchForAppIconSwitch] — and a process that dies before that simply keeps the icon it
 * had, which is the safe half of the trade.
 */
private var pendingVariant: AppIconVariant? by mutableStateOf(null)

/**
 * This process's started activities. Held weakly and by instance, so the stop of an activity
 * that started before the watch began finds nothing to remove.
 */
private val startedActivities: MutableSet<Activity> =
    Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

private var switchWatchRegistered = false

private fun enabledAppIconVariant(): AppIconVariant {
    val context = androidAppContext ?: return AppIconVariant.Default
    val manager = context.packageManager
    return AppIconVariant.entries.firstOrNull { variant ->
        val component = ComponentName(context.packageName, variant.componentClass())
        manager.getComponentEnabledSetting(component) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } ?: AppIconVariant.Default
}

actual fun currentAppIconVariant(): AppIconVariant = pendingVariant ?: enabledAppIconVariant()

actual fun setAppIconVariant(variant: AppIconVariant) {
    // Chosen now, applied on the way out. Everything that asks what the icon is goes through
    // [currentAppIconVariant], which answers with the pending choice, so the settings page and
    // the splash pairing both behave as though it had already happened.
    pendingVariant = variant.takeIf { it != enabledAppIconVariant() }
}

/**
 * Applies a chosen icon when the last started activity stops and the user has left the app.
 *
 * MainActivity's own onStop is not that moment: it also runs under the full-screen player, the
 * QR scanner and system pickers, all in the same task, and switching then took the task — and
 * the player the user had just opened — down with it. Called from MainActivity.onCreate, before
 * any choice can be made; later calls do nothing.
 */
fun watchForAppIconSwitch(application: Application) {
    if (switchWatchRegistered) return
    switchWatchRegistered = true
    application.registerActivityLifecycleCallbacks(
        object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities += activity
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities -= activity
                // A configuration change stops an activity only to start its replacement.
                if (pendingVariant == null || activity.isChangingConfigurations) return
                if (startedActivities.isEmpty() && userHasLeft(activity.applicationContext)) {
                    applyPendingAppIconVariant()
                }
            }

            override fun onActivityDestroyed(activity: Activity) {
                startedActivities -= activity
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit
        },
    )
}

/**
 * Whether nothing is left standing on top of MainActivity for the switch to close.
 *
 * With no activity of ours started, the task can still be in front: a document or folder
 * picker opened from 设置 belongs to another app but runs in Yfuse's task, and would close
 * under the user. A locked screen is not leaving either — 返回桌面后更新图标 is the promise.
 * Either way the choice waits for the next time the app is left.
 */
private fun userHasLeft(context: Context): Boolean {
    if (context.getSystemService(PowerManager::class.java)?.isInteractive == false) return false
    // A task's top activity is public from Android 10; before that, no started activity is
    // the best answer there is.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
    val ownEntries = AppIconVariant.entries.map { it.componentClass() } + APP_ENTRY_ALIAS
    return runCatching {
        context.getSystemService(ActivityManager::class.java)?.appTasks.orEmpty().all { task ->
            val top = task.taskInfo.topActivity
            top == null || (top.packageName == context.packageName && top.className in ownEntries)
        }
    }.getOrDefault(false)
}

/** Hands any deferred choice to the package manager. Safe to call when there is none. */
private fun applyPendingAppIconVariant() {
    val variant = pendingVariant ?: return
    pendingVariant = null
    val context = androidAppContext ?: return
    val manager = context.packageManager
    // Enable the target before disabling the others. The launcher reads the enabled
    // LAUNCHER components, and with no enabled component even momentarily, some launchers
    // drop the app from the drawer and do not put it back until reboot.
    runCatching {
        manager.setComponentEnabledSetting(
            ComponentName(context.packageName, variant.componentClass()),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        AppIconVariant.entries
            .filter { it != variant }
            .forEach { other ->
                manager.setComponentEnabledSetting(
                    ComponentName(context.packageName, other.componentClass()),
                    // MainActivity is enabled in its manifest, so it has to be switched off
                    // outright; the aliases only go back to their manifest state, disabled.
                    if (other == AppIconVariant.Default) {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    },
                    PackageManager.DONT_KILL_APP,
                )
            }
        // Launcher shortcuts belong to the launcher activity they were published under, and
        // Android withdraws them when it is disabled; publish them again under the new one.
        scheduleShortcutUpdate(context)
    }.onFailure { error ->
        AppLog.warning(
            category = "appearance.appIcon",
            event = "switch_failed",
            message = "The launcher icon could not be switched",
            throwable = error,
            attributes = mapOf("variant" to variant.name),
        )
    }
}
