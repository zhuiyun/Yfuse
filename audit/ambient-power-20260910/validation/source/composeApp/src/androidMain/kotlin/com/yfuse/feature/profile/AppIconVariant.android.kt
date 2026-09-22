package com.yfuse.feature.profile

import android.content.ComponentName
import android.content.pm.PackageManager
import com.yfuse.core.logging.AppLog
import com.yfuse.core.util.androidAppContext

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
    }

/**
 * A switch that has been chosen but not yet handed to the package manager.
 *
 * Disabling the component of the activity the user is standing in tears down its task —
 * `DONT_KILL_APP` keeps the *process*, not the task — so applying the choice on the tap
 * dropped the user on their home screen mid-settings. That was tolerable while the only way
 * to reach it was a deliberate "change my launcher icon"; it is not, now that picking a
 * launch animation moves the icon with it (see [SplashMark.appIconFor]).
 *
 * Held in memory only. It is applied the moment the app leaves the foreground — see
 * [applyPendingAppIconVariant] — and a process that dies before that simply keeps the icon
 * it had, which is the safe half of the trade.
 */
private var pendingVariant: AppIconVariant? = null

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
 * Hands any deferred choice to the package manager. Safe to call when there is none.
 *
 * Called from `MainActivity.onStop`, which is the first moment the task can be torn down
 * without the user watching it happen.
 */
fun applyPendingAppIconVariant() {
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
                    // DEFAULT rather than DISABLED for MainActivity: its manifest state is
                    // enabled, and pinning it to DISABLED would survive a switch back.
                    if (other == AppIconVariant.Default) {
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    } else {
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    },
                    PackageManager.DONT_KILL_APP,
                )
            }
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
