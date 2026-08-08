package com.kbmod.cornygw.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.location.Location
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Device heading in degrees clockwise from **true** north.
 *
 * The declination correction matters here and is easy to skip: the compass
 * reports magnetic north, while a bearing derived from two lat/lon pairs is
 * relative to true north. In parts of the world the gap is over 15 degrees,
 * which at 30 m is most of a house — enough to point the user at the wrong
 * neighbour with total confidence.
 */
class HeadingStream(context: Context) {

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    val isAvailable: Boolean
        get() = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    fun headings(locationProvider: () -> Location?): Flow<Double> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            close()
            return@callbackFlow
        }

        val rotationMatrix = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                val (axisX, axisY) = when (displayRotation()) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)
                SensorManager.getOrientation(remapped, orientation)

                val magneticDegrees = Math.toDegrees(orientation[0].toDouble())
                val declination = locationProvider()?.let { fix ->
                    GeomagneticField(
                        fix.latitude.toFloat(),
                        fix.longitude.toFloat(),
                        fix.altitude.toFloat(),
                        System.currentTimeMillis(),
                    ).declination.toDouble()
                } ?: 0.0

                trySend((magneticDegrees + declination + 360.0) % 360.0)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int {
        val windowManager =
            appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        return windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
    }
}
