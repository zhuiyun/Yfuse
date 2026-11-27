package com.yfuse.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal actual fun rememberDockTiltX(enabled: Boolean): State<Float> {
    val tilt = remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(context, lifecycleOwner, enabled) {
        if (!enabled) {
            tilt.floatValue = 0f
            return@DisposableEffect onDispose {}
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor =
            manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            tilt.floatValue = 0f
            return@DisposableEffect onDispose {}
        }
        var filtered = 0f
        var registered = false
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    // Roll left/right mapped softly into [-1f, 1f] (~35 degrees = full sweep).
                    val raw = (-event.values[0] / 5.6f).coerceIn(-1f, 1f)
                    filtered += (raw - filtered) * 0.14f
                    // Deadband avoids 60Hz redraws & GPU churn when the phone rests flat on a table.
                    if (kotlin.math.abs(filtered - tilt.floatValue) > 0.008f) {
                        tilt.floatValue = filtered
                    }
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) = Unit
            }
        fun register() {
            if (!registered) {
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                registered = true
            }
        }
        fun unregister() {
            if (registered) {
                manager.unregisterListener(listener)
                registered = false
            }
        }
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            register()
        }
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> register()
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> unregister()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }
    return tilt
}
