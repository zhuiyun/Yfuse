package com.yfuse.core.designsystem

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Composable
internal actual fun rememberMotionBudget(): MotionBudget {
    val context = LocalContext.current.applicationContext
    val budget = remember(context) { AndroidMotionBudget.observe(context) }
    return budget.collectAsState().value
}

/**
 * One set of listeners for the process, however many windows ask: 省电模式 by its broadcast, the
 * thermal status by PowerManager's listener (API 29+), and low RAM once — it is the device's.
 * Everything here runs on the main thread: the receiver and the listener are both delivered there.
 */
private object AndroidMotionBudget {
    private val state = MutableStateFlow(MotionBudget())
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var powerSave = false
    private var thermal = false
    private var lowRam = false

    /** Cooling hovers around the threshold; the full motion comes back only once it has settled. */
    private val relaxThermal =
        Runnable {
            thermal = false
            publish()
        }

    fun observe(context: Context): StateFlow<MotionBudget> {
        if (!started) {
            started = true
            start(context)
        }
        return state.asStateFlow()
    }

    private fun start(context: Context) {
        lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power != null) {
            powerSave = power.isPowerSaveMode
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?,
                    ) {
                        powerSave = power.isPowerSaveMode
                        publish()
                    }
                }
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            if (Build.VERSION.SDK_INT >= 29) {
                onThermalStatus(Api29MotionThermal.currentStatus(power))
                Api29MotionThermal.addListener(power, ::onThermalStatus)
            }
        }
        publish()
    }

    private fun onThermalStatus(status: Int) {
        if (thermallyConstrained(status)) {
            main.removeCallbacks(relaxThermal)
            if (!thermal) {
                thermal = true
                publish()
            }
        } else if (thermal) {
            main.removeCallbacks(relaxThermal)
            main.postDelayed(relaxThermal, THERMAL_RELAX_MS)
        }
    }

    private fun publish() {
        val next = MotionBudget(powerSave = powerSave, thermal = thermal, lowRam = lowRam)
        if (next == state.value) return
        state.value = next
        AppLog.info(
            category = "performance.motion",
            event = "motion_budget_changed",
            message = if (next.reduced) "Motion drawn as 静息 to spare the device" else "Full motion restored",
            attributes =
                mapOf(
                    "powerSave" to next.powerSave.toString(),
                    "thermal" to next.thermal.toString(),
                    "lowRam" to next.lowRam.toString(),
                ),
        )
    }
}

/** Keeps the API-29 thermal listener types out of the object verified on Android 9 and below. */
@androidx.annotation.RequiresApi(29)
private object Api29MotionThermal {
    fun currentStatus(power: PowerManager): Int = power.currentThermalStatus

    fun addListener(
        power: PowerManager,
        onChanged: (Int) -> Unit,
    ) {
        power.addThermalStatusListener(onChanged)
    }
}

/** How long the thermal status has to stay below MODERATE before the full motion returns. */
private const val THERMAL_RELAX_MS = 30_000L
