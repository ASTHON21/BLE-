package com.example.hardware

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Contract 5.2: Audio & Haptic Geiger Counter Controller
 * - Uses ToneGenerator and VibrationEffect
 * - Pulse frequency increases as distance to target decreases
 * - Safely releases resources on dispose
 */
class GeigerFeedbackController(context: Context) {

  private var toneGenerator: ToneGenerator? =
    try {
      ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    } catch (_: Throwable) {
      null
    }

  private val vibrator: Vibrator? =
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      }
    } catch (_: Throwable) {
      null
    }

  /** Computes pulse interval in ms based on estimated target distance (meters). */
  fun computePulseIntervalMs(distanceMeters: Float): Long {
    val clampedDist = if (distanceMeters.isFinite()) distanceMeters.coerceIn(0.15f, 12.0f) else 6.0f
    // 0.15m -> ~130ms rapid lock beep; 8m+ -> 1150ms slow sonar ping
    return (110L + (clampedDist * 135f).toLong()).coerceIn(120L, 1200L)
  }

  fun triggerPulse(distanceMeters: Float) {
    val isVeryClose = distanceMeters < 1.2f
    try {
      val toneType =
        if (isVeryClose) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP
      val durationMs = if (isVeryClose) 45 else 28
      toneGenerator?.startTone(toneType, durationMs)
    } catch (_: Throwable) {
      // Ignore audio focus / hardware exceptions on headless emulators
    }

    try {
      val vib = vibrator
      if (vib != null && vib.hasVibrator()) {
        val duration = if (isVeryClose) 35L else 18L
        val amplitude = if (isVeryClose) 220 else 120
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          vib.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        } else {
          @Suppress("DEPRECATION") vib.vibrate(duration)
        }
      }
    } catch (_: Throwable) {
      // Ignore vibration security/hardware exceptions
    }
  }

  fun release() {
    try {
      toneGenerator?.release()
    } catch (_: Throwable) {
      // Ignore
    } finally {
      toneGenerator = null
    }
  }
}
