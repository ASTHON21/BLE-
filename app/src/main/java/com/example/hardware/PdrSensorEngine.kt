package com.example.hardware

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Contract 2 + Weinberg Adaptive Step Estimator:
 * - Azimuth circular EMA smoothing: smoothSin / smoothCos with alpha = 0.2f -> atan2(smoothSin, smoothCos)
 * - Step detection chain:
 *   Priority A: TYPE_STEP_DETECTOR (when present and ACTIVITY_RECOGNITION granted) + Weinberg step length
 *   Priority B: TYPE_ACCELEROMETER magnitude sqrt(x^2+y^2+z^2) > 11.5f m/s^2 with 350ms debounce + Weinberg step length
 *   Priority C: Manual virtual D-Pad stepping
 */
class PdrSensorEngine(
  private val context: Context,
  private val onAzimuthUpdated: (azimuthRad: Float, smoothSin: Float, smoothCos: Float) -> Unit,
  private val onHardwareStepDetected: (dynamicStepLengthM: Float) -> Unit,
) : SensorEventListener {

  companion object {
    const val EMA_ALPHA = 0.2f
    const val ACCEL_STEP_THRESHOLD = 11.5f
    const val ACCEL_STEP_COOLDOWN_MS = 350L
    const val DEFAULT_STEP_LENGTH_M = 0.65f
    private const val WEINBERG_K = 0.43f
  }

  private val sensorManager: SensorManager? =
    context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

  private val rotationVectorSensor: Sensor? =
    sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
  private val accelerometerSensor: Sensor? =
    sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
  private val magneticFieldSensor: Sensor? =
    sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
  private val stepDetectorSensor: Sensor? =
    sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

  private val rotationMatrix = FloatArray(9)
  private val orientationAngles = FloatArray(3)
  private val gravityValues = FloatArray(3)
  private val geomagneticValues = FloatArray(3)
  private var hasGravity = false
  private var hasGeomagnetic = false

  private var smoothSin = 0f
  private var smoothCos = 1f
  private var isAzimuthInitialized = false

  private var windowAccelMax = 9.8f
  private var windowAccelMin = 9.8f
  private var lastStepEventElapsedMs = 0L
  private var isListening = false

  private fun hasActivityRecognitionPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
        PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }

  val stepSensorTierName: String
    get() =
      when {
        stepDetectorSensor != null && hasActivityRecognitionPermission() ->
          "L3-A: STEP_DETECTOR+Weinberg"
        accelerometerSensor != null -> "L3-B: ACCEL_PEAK+Weinberg"
        else -> "L3-C: VIRTUAL_DPAD"
      }

  val orientationSensorTierName: String
    get() =
      when {
        rotationVectorSensor != null -> "ROTATION_VECTOR (sin/cos EMA)"
        accelerometerSensor != null && magneticFieldSensor != null ->
          "ACCEL+MAG (sin/cos EMA)"
        else -> "VIRTUAL_HEADING"
      }

  fun start() {
    if (isListening || sensorManager == null) return
    isListening = true

    if (rotationVectorSensor != null) {
      sensorManager.registerListener(
        this,
        rotationVectorSensor,
        SensorManager.SENSOR_DELAY_GAME,
      )
    } else if (accelerometerSensor != null && magneticFieldSensor != null) {
      sensorManager.registerListener(
        this,
        accelerometerSensor,
        SensorManager.SENSOR_DELAY_GAME,
      )
      sensorManager.registerListener(
        this,
        magneticFieldSensor,
        SensorManager.SENSOR_DELAY_GAME,
      )
    }

    if (stepDetectorSensor != null) {
      sensorManager.registerListener(
        this,
        stepDetectorSensor,
        SensorManager.SENSOR_DELAY_FASTEST,
      )
    }
    if (accelerometerSensor != null) {
      sensorManager.registerListener(
        this,
        accelerometerSensor,
        SensorManager.SENSOR_DELAY_GAME,
      )
    }
  }

  fun stop() {
    if (!isListening) return
    isListening = false
    sensorManager?.unregisterListener(this)
  }

  fun feedRawAzimuthRad(rawAzimuthRad: Float) {
    val s = sin(rawAzimuthRad)
    val c = cos(rawAzimuthRad)
    if (!isAzimuthInitialized) {
      smoothSin = s
      smoothCos = c
      isAzimuthInitialized = true
    } else {
      smoothSin = (1f - EMA_ALPHA) * smoothSin + EMA_ALPHA * s
      smoothCos = (1f - EMA_ALPHA) * smoothCos + EMA_ALPHA * c
    }
    val smoothedRad = atan2(smoothSin, smoothCos)
    onAzimuthUpdated(smoothedRad, smoothSin, smoothCos)
  }

  private fun computeWeinbergStepLength(): Float {
    val peakToValley = (windowAccelMax - windowAccelMin).coerceIn(0f, 25f)
    windowAccelMax = 9.8f
    windowAccelMin = 9.8f
    if (peakToValley < 0.8f) return DEFAULT_STEP_LENGTH_M
    val estimated = WEINBERG_K * peakToValley.toDouble().pow(0.25).toFloat()
    return if (estimated.isFinite()) estimated.coerceIn(0.42f, 0.92f) else DEFAULT_STEP_LENGTH_M
  }

  override fun onSensorChanged(event: SensorEvent?) {
    event ?: return
    when (event.sensor.type) {
      Sensor.TYPE_ROTATION_VECTOR -> {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        val rawAzimuth = orientationAngles[0]
        if (rawAzimuth.isFinite()) {
          feedRawAzimuthRad(rawAzimuth)
        }
      }

      Sensor.TYPE_ACCELEROMETER -> {
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]

        if (rotationVectorSensor == null) {
          gravityValues[0] = ax
          gravityValues[1] = ay
          gravityValues[2] = az
          hasGravity = true
          updateFallbackOrientation()
        }

        val magnitude = sqrt(ax * ax + ay * ay + az * az)
        if (magnitude.isFinite()) {
          if (magnitude > windowAccelMax) windowAccelMax = magnitude
          if (magnitude < windowAccelMin) windowAccelMin = magnitude

          // Priority B: Active when TYPE_STEP_DETECTOR is missing OR ACTIVITY_RECOGNITION is ungranted
          // Also acts as automatic fallback if hardware step detector hasn't fired within cooldown
          val nowMs = SystemClock.elapsedRealtime()
          if (
            magnitude > ACCEL_STEP_THRESHOLD &&
              nowMs - lastStepEventElapsedMs > ACCEL_STEP_COOLDOWN_MS
          ) {
            lastStepEventElapsedMs = nowMs
            onHardwareStepDetected(computeWeinbergStepLength())
          }
        }
      }

      Sensor.TYPE_MAGNETIC_FIELD -> {
        if (rotationVectorSensor == null) {
          geomagneticValues[0] = event.values[0]
          geomagneticValues[1] = event.values[1]
          geomagneticValues[2] = event.values[2]
          hasGeomagnetic = true
          updateFallbackOrientation()
        }
      }

      Sensor.TYPE_STEP_DETECTOR -> {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastStepEventElapsedMs > ACCEL_STEP_COOLDOWN_MS) {
          lastStepEventElapsedMs = nowMs
          onHardwareStepDetected(computeWeinbergStepLength())
        }
      }
    }
  }

  private fun updateFallbackOrientation() {
    if (!hasGravity || !hasGeomagnetic) return
    val success =
      SensorManager.getRotationMatrix(rotationMatrix, null, gravityValues, geomagneticValues)
    if (success) {
      SensorManager.getOrientation(rotationMatrix, orientationAngles)
      val rawAzimuth = orientationAngles[0]
      if (rawAzimuth.isFinite()) {
        feedRawAzimuthRad(rawAzimuth)
      }
    }
  }

  override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
