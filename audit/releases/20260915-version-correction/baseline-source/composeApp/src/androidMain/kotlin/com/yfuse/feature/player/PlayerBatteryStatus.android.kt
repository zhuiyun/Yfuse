package com.yfuse.feature.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
internal actual fun rememberPlayerBattery(): State<PlayerBattery?> {
    val context = LocalContext.current.applicationContext
    val status = remember(context) { mutableStateOf<PlayerBattery?>(null) }
    DisposableEffect(context) {
        fun update(intent: Intent?) {
            if (intent == null || !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) {
                status.value = null
                return
            }
            val percent =
                playerBatteryPercent(
                    intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                    intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                )
            status.value = percent?.let { PlayerBattery(it, intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) }
        }
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    update(intent)
                }
            }
        val sticky =
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        update(sticky)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return status
}
