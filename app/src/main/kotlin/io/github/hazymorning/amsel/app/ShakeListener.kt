package io.github.hazymorning.amsel.app

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

// Several shakes in a row, so a single bump in a pocket does not count.
internal class ShakeListener(private val onShake: () -> Unit) : SensorEventListener {
    private var count = 0
    private var firstAt = 0L
    private var lastShake = 0L

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < THRESHOLD_G) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastShake < COOL_DOWN_MS) return
        if (now - firstAt > WINDOW_MS) {
            firstAt = now
            count = 0
        }
        count++
        if (count >= SHAKES) {
            count = 0
            lastShake = now
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val THRESHOLD_G = 2.2f
        const val WINDOW_MS = 1_500L
        const val COOL_DOWN_MS = 3_000L
        const val SHAKES = 3
    }
}
