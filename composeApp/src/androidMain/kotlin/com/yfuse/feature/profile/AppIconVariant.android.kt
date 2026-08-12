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
private fun AppIconVariant.componentClass(): String = when (this) {
    AppIconVariant.Default -> "com.yfuse.MainActivity"
    AppIconVariant.Graphite -> "com.yfuse.LauncherGraphite"
    AppIconVariant.CloudPlayer -> "com.yfuse.LauncherCloud"
}

actual fun currentAppIconVariant(): AppIconVariant {
    val context = androidAppContext ?: return AppIconVariant.Default
    val manager = context.packageManager
    return AppIconVariant.entries.firstOrNull { variant ->
        val component = ComponentName(context.packageName, variant.componentClass())
        manager.getComponentEnabledSetting(component) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } ?: AppIconVariant.Default
}

actual fun setAppIconVariant(variant: AppIconVariant) {
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
