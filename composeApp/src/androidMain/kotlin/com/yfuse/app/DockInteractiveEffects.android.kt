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

@Composable
internal actual fun rememberDockTiltX(enabled: Boolean): State<Float> {
    val tilt = remember { mutableFloatStateOf(0f) }
    val context = LocalContext.current
    DisposableEffect(context, enabled) {
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
        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    // Roll left/right mapped softly into [-1f, 1f] (~35 degrees = full sweep).
                    val raw = (-event.values[0] / 5.6f).coerceIn(-1f, 1f)
                    filtered += (raw - filtered) * 0.14f
                    tilt.floatValue = filtered
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) = Unit
            }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose {
            manager.unregisterListener(listener)
        }
    }
    return tilt
}
