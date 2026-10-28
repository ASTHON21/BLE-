package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.BleDeviceTelemetry
import com.example.model.OscilloscopeTrace
import com.example.ui.theme.RadarAmber
import com.example.ui.theme.RadarBgCard
import com.example.ui.theme.RadarBorder
import com.example.ui.theme.RadarCrimson
import com.example.ui.theme.RadarCyan
import com.example.ui.theme.RadarEmerald
import com.example.ui.theme.RadarTextPrimary
import com.example.ui.theme.RadarTextSecondary
import kotlin.math.abs

/**
 * L2 Three-Trace Real-Time RF Oscilloscope:
 * Visualizes the 7-window Hampel 3xMAD outlier clamp band, raw noisy RSSI spikes,
 * Hampel clamped intermediate signal, and 1D Kalman smoothed output in real time.
 */
@Composable
fun RfOscilloscopeCard(
  lockedDevice: BleDeviceTelemetry?,
  trace: OscilloscopeTrace,
  modifier: Modifier = Modifier,
) {
  val rawPath = remember { Path() }
  val hampelPath = remember { Path() }
  val kalmanPath = remember { Path() }
  val bandPath = remember { Path() }
  val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f) }

  Card(
    modifier = modifier.fillMaxWidth().height(126.dp).testTag("rf_oscilloscope_card"),
    colors = CardDefaults.cardColors(containerColor = RadarBgCard),
    border = BorderStroke(1.dp, RadarBorder),
    shape = RoundedCornerShape(12.dp),
  ) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text =
            "L2 射频时序示波器 · ${lockedDevice?.deviceName ?: "等待信标"}",
          style = MaterialTheme.typography.labelMedium,
          color = RadarCyan,
        )
        Row(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          TraceLegendDot(color = RadarCrimson, label = "原始多径/尖峰")
          TraceLegendDot(color = RadarAmber, label = "Hampel钳制")
          TraceLegendDot(color = RadarCyan, label = "1D卡尔曼")
        }
      }

      Spacer(modifier = Modifier.height(4.dp))

      Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
          val w = size.width
          val h = size.height

          // Horizontal dBm reference grid lines (-45, -65, -85, -100 dBm)
          val minDbm = -105f
          val maxDbm = -30f
          fun dbmToY(dbm: Float): Float {
            val norm = ((dbm.coerceIn(minDbm, maxDbm) - minDbm) / (maxDbm - minDbm))
            return h * (1f - norm)
          }

          for (refDbm in listOf(-45f, -65f, -85f)) {
            val y = dbmToY(refDbm)
            drawLine(
              color = RadarBorder.copy(alpha = 0.55f),
              start = Offset(0f, y),
              end = Offset(w, y),
              strokeWidth = 1.dp.toPx(),
              pathEffect = dashEffect,
            )
          }

          val n = trace.count
          if (n >= 2) {
            val stepX = w / (n - 1).coerceAtLeast(1)

            rawPath.reset()
            hampelPath.reset()
            kalmanPath.reset()
            bandPath.reset()

            // Build Hampel 3xMAD Envelope Band Path
            for (i in 0 until n) {
              val x = i * stepX
              val yu = dbmToY(trace.upperBandHistory[i])
              if (i == 0) bandPath.moveTo(x, yu) else bandPath.lineTo(x, yu)
            }
            for (i in (n - 1) downTo 0) {
              val x = i * stepX
              val yl = dbmToY(trace.lowerBandHistory[i])
              bandPath.lineTo(x, yl)
            }
            bandPath.close()
            drawPath(path = bandPath, color = RadarEmerald.copy(alpha = 0.08f))

            for (i in 0 until n) {
              val x = i * stepX
              val yRaw = dbmToY(trace.rawHistory[i])
              val yHampel = dbmToY(trace.hampelHistory[i])
              val yKalman = dbmToY(trace.kalmanHistory[i])

              if (i == 0) {
                rawPath.moveTo(x, yRaw)
                hampelPath.moveTo(x, yHampel)
                kalmanPath.moveTo(x, yKalman)
              } else {
                rawPath.lineTo(x, yRaw)
                hampelPath.lineTo(x, yHampel)
                kalmanPath.lineTo(x, yKalman)
              }

              // Highlight multipath spikes clamped by Hampel filter (> 0.5 dB difference)
              if (abs(trace.rawHistory[i] - trace.hampelHistory[i]) > 0.5f) {
                drawCircle(
                  color = RadarCrimson,
                  radius = 3.2.dp.toPx(),
                  center = Offset(x, yRaw),
                )
              }
            }

            // 1. Raw noisy RSSI trace
            drawPath(
              path = rawPath,
              color = RadarCrimson.copy(alpha = 0.55f),
              style = Stroke(width = 1.2.dp.toPx()),
            )
            // 2. Hampel outlier-clamped trace
            drawPath(
              path = hampelPath,
              color = RadarAmber.copy(alpha = 0.78f),
              style = Stroke(width = 1.5.dp.toPx()),
            )
            // 3. 1D Kalman smoothed trace
            drawPath(
              path = kalmanPath,
              color = RadarCyan,
              style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round),
            )
          }
        }

        // Bottom-left innovation residual readout
        if (lockedDevice != null) {
          val spikeClamped = abs(lockedDevice.rawRssi.toFloat() - lockedDevice.hampelRssi)
          Text(
            text =
              if (spikeClamped > 0.5f) {
                "Hampel 已削峰: ${spikeClamped.format1()} dB | Kalman: ${lockedDevice.smoothedRssi.format1()} dBm"
              } else {
                "原始: ${lockedDevice.rawRssi} dBm → 卡尔曼: ${lockedDevice.smoothedRssi.format1()} dBm"
              },
            style = MaterialTheme.typography.labelSmall,
            color = if (spikeClamped > 0.5f) RadarCrimson else RadarTextSecondary,
            modifier = Modifier.align(Alignment.BottomStart),
          )
        }
      }
    }
  }
}

@Composable
private fun TraceLegendDot(color: Color, label: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
    Spacer(modifier = Modifier.width(3.dp))
    Text(text = label, style = MaterialTheme.typography.labelSmall, color = RadarTextPrimary)
  }
}
