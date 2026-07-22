package org.ryubing.android.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.view.OrientationEventListener

/**
 * Feeds device accelerometer/gyro into the emulator. Axis remapping follows Kenji-NX's
 * landscape handheld convention.
 */
class MotionSensorManager(
    context: Context,
    private val onMotion: (ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) -> Unit,
) : SensorEventListener2 {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var isRegistered = false

    private val motionGyroOrientation = FloatArray(3)
    private val motionAcelOrientation = FloatArray(3)
    private var lastAx = 0f
    private var lastAy = 0f
    private var lastAz = 0f
    private var lastGx = 0f
    private var lastGy = 0f
    private var lastGz = 0f

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            when {
                isWithinOrientationRange(orientation, 270) -> setOrientation270()
                isWithinOrientationRange(orientation, 90) -> setOrientation90()
            }
        }
    }

    init {
        setOrientation90()
        orientationListener.enable()
    }

    fun register() {
        if (isRegistered) return
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accelerometer?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        isRegistered = true
    }

    fun unregister() {
        if (!isRegistered) return
        sensorManager.unregisterListener(this)
        isRegistered = false
        onMotion(0f, 0f, 0f, 0f, 0f, 0f)
    }

    fun release() {
        unregister()
        orientationListener.disable()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isRegistered || event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAx = motionAcelOrientation[0] * event.values[1]
                lastAy = motionAcelOrientation[1] * event.values[0]
                lastAz = motionAcelOrientation[2] * event.values[2]
            }
            Sensor.TYPE_GYROSCOPE -> {
                lastGx = motionGyroOrientation[0] * event.values[1]
                lastGy = motionGyroOrientation[1] * event.values[0]
                lastGz = motionGyroOrientation[2] * event.values[2]
            }
            else -> return
        }
        onMotion(lastAx, lastAy, lastAz, lastGx, lastGy, lastGz)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onFlushCompleted(sensor: Sensor?) = Unit

    private fun setOrientation270() {
        motionGyroOrientation[0] = -1f
        motionGyroOrientation[1] = 1f
        motionGyroOrientation[2] = 1f
        motionAcelOrientation[0] = 1f
        motionAcelOrientation[1] = -1f
        motionAcelOrientation[2] = -1f
    }

    private fun setOrientation90() {
        motionGyroOrientation[0] = 1f
        motionGyroOrientation[1] = -1f
        motionGyroOrientation[2] = 1f
        motionAcelOrientation[0] = -1f
        motionAcelOrientation[1] = 1f
        motionAcelOrientation[2] = -1f
    }

    private fun isWithinOrientationRange(
        current: Int,
        target: Int,
        epsilon: Int = 90,
    ): Boolean = current > target - epsilon && current < target + epsilon
}
