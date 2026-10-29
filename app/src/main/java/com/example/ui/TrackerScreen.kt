package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hardware.GeigerFeedbackController
import com.example.model.BleDeviceTelemetry
import com.example.model.ParticleSnapshot
import com.example.model.PdrState
import com.example.ui.theme.RadarAmber
import com.example.ui.theme.RadarBgCard
import com.example.ui.theme.RadarBgDeep
import com.example.ui.theme.RadarBgElevated
import com.example.ui.theme.RadarBorder
import com.example.ui.theme.RadarCrimson
import com.example.ui.theme.RadarCyan
import com.example.ui.theme.RadarEmerald
import com.example.ui.theme.RadarOrange
import com.example.ui.theme.RadarPurple
import com.example.ui.theme.RadarTextPrimary
import com.example.ui.theme.RadarTextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun TrackerScreen(
  bodyShadowingEnabled: Boolean,
  lockedDevice: BleDeviceTelemetry?,
  pdrState: PdrState,
  particleSnapshot: ParticleSnapshot,
  defaultTxPower: Int,
  basePathLossN: Float,
  geigerEnabled: Boolean,
  onToggleBodyShadowing: (Boolean) -> Unit,
  onStepDirection: (dx: Float, dy: Float) -> Unit,
  onRotateHeading: (deltaDegrees: Float) -> Unit,
  onResetParticles: () -> Unit,
  onRecordHuntSession: () -> Unit,
  onToggleGeiger: (Boolean) -> Unit,
  onUpdateDefaultTxPower: (Int) -> Unit,
  onUpdateBasePathLossN: (Float) -> Unit,
  onNavigateBackToScanner: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onNavigateBackToScanner)

  val context = LocalContext.current
  val geigerController = remember { GeigerFeedbackController(context.applicationContext) }

  // Contract 5.2: Safely release ToneGenerator in onDispose
  DisposableEffect(geigerController) { onDispose { geigerController.release() } }

  LaunchedEffect(geigerEnabled, lockedDevice?.isSignalStale) {
    if (!geigerEnabled) return@LaunchedEffect
    while (isActive && geigerEnabled) {
      val isStale = lockedDevice?.isSignalStale == true
      val dist = particleSnapshot.estDistanceM
      if (!isStale) {
        geigerController.triggerPulse(dist)
      }
      val intervalMs = geigerController.computePulseIntervalMs(dist)
      delay(intervalMs)
    }
  }

  var showCalibrationSliders by remember { mutableStateOf(false) }
  var savedSessionBannerVisible by remember { mutableStateOf(false) }

  LaunchedEffect(savedSessionBannerVisible) {
    if (savedSessionBannerVisible) {
      delay(2200L)
      savedSessionBannerVisible = false
    }
  }

  Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
    // 1. Contract 3.1: SIGNAL_STALE Banner when packet interval > 3000ms
    AnimatedVisibility(visible = lockedDevice?.isSignalStale == true) {
      Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("signal_stale_banner"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF331019)),
        border = BorderStroke(1.dp, RadarCrimson),
        shape = RoundedCornerShape(10.dp),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.WarningAmber,
            contentDescription = "Signal Stale",
            tint = RadarCrimson,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "等待广播包... (超过 3000ms 未收包，SIGNAL_STALE 已暂停粒子重采样)",
            style = MaterialTheme.typography.labelMedium,
            color = RadarTextPrimary,
          )
        }
      }
    }

    // 2. Top L4+ Target Convergence & Bimodal Advisor HUD
    ConvergenceHudCard(
      lockedDevice = lockedDevice,
      pdrState = pdrState,
      snapshot = particleSnapshot,
    )

    Spacer(modifier = Modifier.height(6.dp))

    // 3. Core 60fps 2D Spatial Particle Radar Canvas (2500 SoA Zero-GC Particles)
    Box(
      modifier =
        Modifier.fillMaxWidth()
          .weight(1f)
          .clip(RoundedCornerShape(14.dp))
          .background(RadarBgDeep)
          .testTag("particle_radar_canvas")
    ) {
      // Read snapshot.version to ensure 60fps Canvas invalidation with Ping-Pong buffer pool
      val renderVersion = particleSnapshot.version
      SpatialParticleRadarCanvas(
        renderVersion = renderVersion,
        pdrState = pdrState,
        snapshot = particleSnapshot,
      )

      // Top-left Legend Overlay inside Canvas
      Column(
        modifier =
          Modifier.align(Alignment.TopStart)
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(RadarBgCard.copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        LegendRow(
          color = RadarOrange,
          text =
            "主峰准星 (${particleSnapshot.estTargetX.format1()}m, ${particleSnapshot.estTargetY.format1()}m)",
        )
        if (particleSnapshot.hasBimodalAmbiguity) {
          LegendRow(
            color = RadarPurple,
            text =
              "镜像幽灵次峰 (${particleSnapshot.ghostTargetX.format1()}m, ${particleSnapshot.ghostTargetY.format1()}m)",
          )
        }
        LegendRow(
          color = RadarCyan,
          text =
            "PDR (${pdrState.userX.format1()}m, ${pdrState.userY.format1()}m | 步幅 ${pdrState.lastWeinbergStepM.format2()}m)",
        )
      }

      // Top-right N_eff & Zero-GC Pool Badge
      Column(
        modifier =
          Modifier.align(Alignment.TopEnd)
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(RadarBgCard.copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.End,
      ) {
        Text(
          text = "N_eff: ${particleSnapshot.nEff.roundToInt()} / ${particleSnapshot.count}",
          style = MaterialTheme.typography.labelSmall,
          color = RadarCyan,
        )
        Text(
          text = "重采样: ${particleSnapshot.resampleCount}次 | n̄=${particleSnapshot.meanPathLossN.format2()}",
          style = MaterialTheme.typography.labelSmall,
          color = RadarAmber,
        )
      }

      // Bottom Tactical Step Advisor Pill inside Canvas
      Surface(
        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
        color = RadarBgCard.copy(alpha = 0.90f),
        shape = RoundedCornerShape(8.dp),
        border =
          BorderStroke(
            1.dp,
            if (particleSnapshot.hasBimodalAmbiguity) RadarPurple else RadarCyan.copy(alpha = 0.5f),
          ),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.Explore,
            contentDescription = "Step Advisor",
            tint = if (particleSnapshot.hasBimodalAmbiguity) RadarPurple else RadarCyan,
            modifier = Modifier.size(14.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text =
              if (savedSessionBannerVisible) "已将当前收敛坐标写入黑匣子数据库！"
              else particleSnapshot.orthogonalStepHint,
            style = MaterialTheme.typography.labelSmall,
            color = if (savedSessionBannerVisible) RadarEmerald else RadarTextPrimary,
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(6.dp))

    // 4. Bottom Collapsible Control Pod
    BottomControlPod(
      geigerEnabled = geigerEnabled,
      bodyShadowingEnabled = bodyShadowingEnabled,
      defaultTxPower = defaultTxPower,
      basePathLossN = basePathLossN,
      showCalibrationSliders = showCalibrationSliders,
      onToggleCalibrationSliders = { showCalibrationSliders = !showCalibrationSliders },
      onStepDirection = onStepDirection,
      onRotateHeading = onRotateHeading,
      onResetParticles = onResetParticles,
      onRecordHuntSession = {
        onRecordHuntSession()
        savedSessionBannerVisible = true
      },
      onToggleGeiger = onToggleGeiger,
      onToggleBodyShadowing = onToggleBodyShadowing,
      onUpdateDefaultTxPower = onUpdateDefaultTxPower,
      onUpdateBasePathLossN = onUpdateBasePathLossN,
    )

    Spacer(modifier = Modifier.height(6.dp))
  }
}

@Composable
private fun LegendRow(color: Color, text: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
    Spacer(modifier = Modifier.width(5.dp))
    Text(text = text, style = MaterialTheme.typography.labelSmall, color = RadarTextPrimary)
  }
}

@Composable
private fun ConvergenceHudCard(
  lockedDevice: BleDeviceTelemetry?,
  pdrState: PdrState,
  snapshot: ParticleSnapshot,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = RadarBgCard),
    border = BorderStroke(1.dp, RadarBorder),
    shape = RoundedCornerShape(12.dp),
  ) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text =
              lockedDevice?.let { "${it.deviceName} (${it.vendorName})" } ?: "未选择信标",
            style = MaterialTheme.typography.titleMedium,
            color = RadarTextPrimary,
          )
          Text(
            text =
              lockedDevice?.let {
                "MAC: ${it.macAddress} | 平滑: ${it.smoothedRssi.format1()} dBm"
              } ?: "请先从声呐台选择信标",
            style = MaterialTheme.typography.labelSmall,
            color = RadarTextSecondary,
          )
        }

        Surface(
          color =
            if (snapshot.confidencePct >= 65f) RadarEmerald.copy(alpha = 0.2f)
            else RadarOrange.copy(alpha = 0.2f),
          shape = RoundedCornerShape(8.dp),
          border =
            BorderStroke(
              1.dp,
              if (snapshot.confidencePct >= 65f) RadarEmerald else RadarOrange,
            ),
        ) {
          Text(
            text = "置信度 ${snapshot.confidencePct.format1()}%",
            style = MaterialTheme.typography.labelMedium,
            color = if (snapshot.confidencePct >= 65f) RadarEmerald else RadarOrange,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
      }

      Spacer(modifier = Modifier.height(5.dp))

      val headingDeg =
        ((Math.toDegrees(pdrState.azimuthRad.toDouble()).toFloat() % 360f) + 360f) % 360f
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        HudMetricItem(
          label = "主峰锁定距离",
          value = "${snapshot.estDistanceM.format2()} m",
          color = RadarOrange,
        )
        HudMetricItem(
          label = "相对方位角",
          value =
            "${if (snapshot.relativeBearingDeg >= 0) "+" else ""}${snapshot.relativeBearingDeg.format1()}°",
          color = RadarCyan,
        )
        HudMetricItem(
          label = "平滑朝向角",
          value = "${headingDeg.format1()}°",
          color = RadarEmerald,
        )
        HudMetricItem(
          label = "有效 TxPower",
          value = "${lockedDevice?.effectiveTxPower ?: -59} dBm",
          color = RadarTextPrimary,
        )
      }
    }
  }
}

@Composable
private fun HudMetricItem(label: String, value: String, color: Color) {
  Column {
    Text(text = label, style = MaterialTheme.typography.labelSmall, color = RadarTextSecondary)
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium,
      color = color,
      fontWeight = FontWeight.Bold,
    )
  }
}

@Composable
private fun SpatialParticleRadarCanvas(
  renderVersion: Long,
  pdrState: PdrState,
  snapshot: ParticleSnapshot,
) {
  val textMeasurer = rememberTextMeasurer()
  val dashPathEffect = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f) }
  val fovPath = remember { Path() }

  Canvas(modifier = Modifier.fillMaxSize()) {
    // Reference renderVersion so Compose redraws on every Ping-Pong update
    if (renderVersion < 0L) return@Canvas

    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f

    val metersHalfSpan = 7.5f
    val pxPerMeter = (size.minDimension / 2f) / metersHalfSpan

    val camX = (pdrState.userX * 0.5f).coerceIn(-10f, 10f)
    val camY = (pdrState.userY * 0.5f).coerceIn(-10f, 10f)

    fun worldToScreenX(wx: Float): Float = cx + (wx - camX) * pxPerMeter
    fun worldToScreenY(wy: Float): Float = cy - (wy - camY) * pxPerMeter

    // 1. Draw Metric Coordinate Grid
    for (gm in -18..18) {
      val sx = worldToScreenX(gm.toFloat())
      if (sx in 0f..w) {
        val isAxis = gm == 0
        val isMajor = gm % 2 == 0
        drawLine(
          color =
            when {
              isAxis -> RadarCyan.copy(alpha = 0.42f)
              isMajor -> RadarBorder.copy(alpha = 0.65f)
              else -> RadarBorder.copy(alpha = 0.28f)
            },
          start = Offset(sx, 0f),
          end = Offset(sx, h),
          strokeWidth = if (isAxis) 1.5.dp.toPx() else 1.dp.toPx(),
        )
      }

      val sy = worldToScreenY(gm.toFloat())
      if (sy in 0f..h) {
        val isAxis = gm == 0
        val isMajor = gm % 2 == 0
        drawLine(
          color =
            when {
              isAxis -> RadarCyan.copy(alpha = 0.42f)
              isMajor -> RadarBorder.copy(alpha = 0.65f)
              else -> RadarBorder.copy(alpha = 0.28f)
            },
          start = Offset(0f, sy),
          end = Offset(w, sy),
          strokeWidth = if (isAxis) 1.5.dp.toPx() else 1.dp.toPx(),
        )
      }
    }

    val originY = worldToScreenY(0f).coerceIn(16f, h - 20f)
    for (gm in listOf(-6, -4, -2, 2, 4, 6)) {
      val sx = worldToScreenX(gm.toFloat())
      if (sx in 20f..(w - 28f)) {
        val label =
          textMeasurer.measure(
            text = "${gm}m",
            style =
              TextStyle(
                color = RadarTextSecondary.copy(alpha = 0.65f),
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
              ),
          )
        drawText(label, topLeft = Offset(sx + 2f, originY + 2f))
      }
    }

    // 2. Draw all 2500 Probability Particles (Zero-GC SoA arrays)
    val n = snapshot.count
    val pxArr = snapshot.px
    val pyArr = snapshot.py
    val pwArr = snapshot.pw
    var maxW = 1f / n
    for (i in 0 until n) {
      if (pwArr[i] > maxW) maxW = pwArr[i]
    }
    val invMaxW = if (maxW > 1e-9f) 1f / maxW else 1f
    val baseParticleRadius = 2.3.dp.toPx()

    for (i in 0 until n) {
      val sx = worldToScreenX(pxArr[i])
      val sy = worldToScreenY(pyArr[i])
      if (sx < -8f || sx > w + 8f || sy < -8f || sy > h + 8f) continue

      val normW = (pwArr[i] * invMaxW).coerceIn(0f, 1f)
      val particleColor =
        lerp(
          start = RadarCyan,
          stop = RadarOrange,
          fraction = normW.coerceIn(0f, 1f),
        )
      val alpha = (0.18f + 0.78f * normW).coerceIn(0.18f, 0.96f)
      val radius = baseParticleRadius * (0.75f + 0.65f * normW)

      drawCircle(
        color = particleColor.copy(alpha = alpha),
        radius = radius,
        center = Offset(sx, sy),
      )
    }

    // 3. Draw Secondary Mirror Ghost Peak Crosshair when Bimodal Ambiguity Exists
    if (snapshot.hasBimodalAmbiguity) {
      val gx = worldToScreenX(snapshot.ghostTargetX)
      val gy = worldToScreenY(snapshot.ghostTargetY)
      val ghostR = 11.dp.toPx()
      drawCircle(
        color = RadarPurple.copy(alpha = 0.22f),
        radius = ghostR * 1.35f,
        center = Offset(gx, gy),
      )
      drawCircle(
        color = RadarPurple,
        radius = ghostR,
        center = Offset(gx, gy),
        style = Stroke(width = 1.6.dp.toPx(), pathEffect = dashPathEffect),
      )
    }

    // 4. Draw User Walking Trajectory Polyline
    val trail = pdrState.trajectory
    if (trail.size >= 2) {
      for (idx in 0 until trail.size - 1) {
        val (x0, y0) = trail[idx]
        val (x1, y1) = trail[idx + 1]
        drawLine(
          color = RadarCyan.copy(alpha = 0.85f),
          start = Offset(worldToScreenX(x0), worldToScreenY(y0)),
          end = Offset(worldToScreenX(x1), worldToScreenY(y1)),
          strokeWidth = 2.5.dp.toPx(),
          cap = StrokeCap.Round,
        )
      }
    }

    // 5. Draw User Current Position, FOV Cone & Orthogonal Step Guide Vector
    val ux = worldToScreenX(pdrState.userX)
    val uy = worldToScreenY(pdrState.userY)

    if (snapshot.hasBimodalAmbiguity) {
      val guideLen = 44.dp.toPx()
      val gxEnd = ux + guideLen * sin(snapshot.recommendedStepRad)
      val gyEnd = uy - guideLen * cos(snapshot.recommendedStepRad)
      drawLine(
        color = RadarPurple,
        start = Offset(ux, uy),
        end = Offset(gxEnd, gyEnd),
        strokeWidth = 2.5.dp.toPx(),
        pathEffect = dashPathEffect,
      )
      drawCircle(color = RadarPurple, radius = 4.dp.toPx(), center = Offset(gxEnd, gyEnd))
    }

    val coneLen = 34.dp.toPx()
    val halfFovRad = 28f * (PI.toFloat() / 180f)
    val leftAngle = pdrState.azimuthRad - halfFovRad
    val rightAngle = pdrState.azimuthRad + halfFovRad

    fovPath.reset()
    fovPath.moveTo(ux, uy)
    fovPath.lineTo(ux + coneLen * sin(leftAngle), uy - coneLen * cos(leftAngle))
    fovPath.lineTo(ux + coneLen * sin(rightAngle), uy - coneLen * cos(rightAngle))
    fovPath.close()
    drawPath(path = fovPath, color = RadarCyan.copy(alpha = 0.26f))

    val arrowLen = 20.dp.toPx()
    drawLine(
      color = RadarCyan,
      start = Offset(ux, uy),
      end =
        Offset(
          ux + arrowLen * sin(pdrState.azimuthRad),
          uy - arrowLen * cos(pdrState.azimuthRad),
        ),
      strokeWidth = 2.5.dp.toPx(),
      cap = StrokeCap.Round,
    )
    drawCircle(color = Color.White, radius = 5.8.dp.toPx(), center = Offset(ux, uy))
    drawCircle(color = RadarCyan, radius = 4.dp.toPx(), center = Offset(ux, uy))

    // 6. Draw Primary Mode Target Lock Crosshair
    val estX = worldToScreenX(snapshot.estTargetX)
    val estY = worldToScreenY(snapshot.estTargetY)
    val crossRadius = 13.dp.toPx()

    drawCircle(
      color = RadarOrange.copy(alpha = 0.22f),
      radius = crossRadius * 1.45f,
      center = Offset(estX, estY),
    )
    drawCircle(
      color = RadarOrange,
      radius = crossRadius,
      center = Offset(estX, estY),
      style = Stroke(width = 2.dp.toPx()),
    )
    drawLine(
      color = RadarOrange,
      start = Offset(estX - crossRadius * 1.5f, estY),
      end = Offset(estX + crossRadius * 1.5f, estY),
      strokeWidth = 2.dp.toPx(),
    )
    drawLine(
      color = RadarOrange,
      start = Offset(estX, estY - crossRadius * 1.5f),
      end = Offset(estX, estY + crossRadius * 1.5f),
      strokeWidth = 2.dp.toPx(),
    )

    drawLine(
      color = RadarOrange.copy(alpha = 0.65f),
      start = Offset(ux, uy),
      end = Offset(estX, estY),
      strokeWidth = 1.5.dp.toPx(),
      pathEffect = dashPathEffect,
    )
  }
}

@Composable
private fun BottomControlPod(
  geigerEnabled: Boolean,
  bodyShadowingEnabled: Boolean,
  defaultTxPower: Int,
  basePathLossN: Float,
  showCalibrationSliders: Boolean,
  onToggleCalibrationSliders: () -> Unit,
  onStepDirection: (dx: Float, dy: Float) -> Unit,
  onRotateHeading: (deltaDegrees: Float) -> Unit,
  onResetParticles: () -> Unit,
  onRecordHuntSession: () -> Unit,
  onToggleGeiger: (Boolean) -> Unit,
  onToggleBodyShadowing: (Boolean) -> Unit,
  onUpdateDefaultTxPower: (Int) -> Unit,
  onUpdateBasePathLossN: (Float) -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = RadarBgCard),
    border = BorderStroke(1.dp, RadarBorder),
    shape = RoundedCornerShape(14.dp),
  ) {
    Column(modifier = Modifier.padding(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Left: Manual Step / Azimuth Calibration Assist Controller
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = "辅助步进与朝向微调",
            style = MaterialTheme.typography.labelSmall,
            color = RadarCyan,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(
              onClick = { onRotateHeading(-45f) },
              colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                  containerColor = RadarBgDeep,
                  contentColor = RadarAmber,
                ),
              modifier = Modifier.size(48.dp).testTag("rotate_left_button"),
            ) {
              Icon(Icons.AutoMirrored.Filled.RotateLeft, contentDescription = "原地左转 45°")
            }
            FilledTonalIconButton(
              onClick = { onStepDirection(0f, 1f) },
              colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                  containerColor = RadarBgElevated,
                  contentColor = RadarCyan,
                ),
              modifier = Modifier.size(48.dp).testTag("step_up_button"),
            ) {
              Icon(Icons.Default.KeyboardArrowUp, contentDescription = "向前迈步 (+Y)")
            }
            FilledTonalIconButton(
              onClick = { onRotateHeading(45f) },
              colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                  containerColor = RadarBgDeep,
                  contentColor = RadarAmber,
                ),
              modifier = Modifier.size(48.dp).testTag("rotate_right_button"),
            ) {
              Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "原地右转 45°")
            }
          }
          Spacer(modifier = Modifier.height(4.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(
              onClick = { onStepDirection(-1f, 0f) },
              colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                  containerColor = RadarBgElevated,
                  contentColor = RadarCyan,
                ),
              modifier = Modifier.size(48.dp).testTag("step_left_button"),
            ) {
              Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "向左迈步 (-X)")
            }
            FilledTonalIconButton(
              onClick = { onStepDirection(0f, -1f) },
              colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                  containerColor = RadarBgElevated,
                  contentColor = RadarCyan,
                ),
              modifier = Modifier.size(48.dp).testTag("step_down_button"),
            ) {
              Icon(Icons.Default.KeyboardArrowDown, contentDescription = "向后迈步 (-Y)")
            }
            FilledTonalIconButton(
              onClick = { onStepDirection(1f, 0f) },
              colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                  containerColor = RadarBgElevated,
                  contentColor = RadarCyan,
                ),
              modifier = Modifier.size(48.dp).testTag("step_right_button"),
            ) {
              Icon(Icons.Default.KeyboardArrowRight, contentDescription = "向右迈步 (+X)")
            }
          }
        }

        Spacer(modifier = Modifier.width(10.dp))

        // Right: Geiger Counter, Reset Particles, Save Blackbox, Calibration Toggle
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Surface(
            color = RadarBgElevated,
            shape = RoundedCornerShape(10.dp),
            border =
              BorderStroke(
                1.dp,
                if (geigerEnabled) RadarOrange else RadarBorder,
              ),
          ) {
            Row(
              modifier =
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                  contentDescription = "Geiger Counter",
                  tint = if (geigerEnabled) RadarOrange else RadarTextSecondary,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                  text = "盖革声振脉冲",
                  style = MaterialTheme.typography.labelMedium,
                  color = RadarTextPrimary,
                )
              }
              Switch(
                checked = geigerEnabled,
                onCheckedChange = onToggleGeiger,
                colors =
                  SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = RadarOrange,
                  ),
                modifier = Modifier.testTag("geiger_switch"),
              )
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
              onClick = onResetParticles,
              colors = ButtonDefaults.buttonColors(containerColor = RadarCyan),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
              modifier = Modifier.weight(1f).testTag("reset_particles_button"),
            ) {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Reset Particle Cloud",
                tint = Color.Black,
                modifier = Modifier.size(14.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "重置粒子",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
              )
            }

            Button(
              onClick = onRecordHuntSession,
              colors = ButtonDefaults.buttonColors(containerColor = RadarEmerald),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
              modifier = Modifier.weight(1f).testTag("record_hunt_button"),
            ) {
              Icon(
                imageVector = Icons.Default.BookmarkAdded,
                contentDescription = "Save Hunt Log",
                tint = Color.Black,
                modifier = Modifier.size(14.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "存入黑匣子",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
              )
            }
          }

          OutlinedButton(
            onClick = onToggleCalibrationSliders,
            border = BorderStroke(1.dp, RadarBorder),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier.fillMaxWidth().testTag("toggle_calibration_button"),
          ) {
            Icon(
              imageVector = Icons.Default.Tune,
              contentDescription = "Toggle environment parameters",
              tint = RadarCyan,
              modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text =
                if (showCalibrationSliders) "收起射频与人体遮挡参数"
                else "展开 TxPower / 衰减n / 人体遮挡",
              style = MaterialTheme.typography.labelSmall,
              color = RadarCyan,
            )
          }
        }
      }

      AnimatedVisibility(visible = showCalibrationSliders) {
        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "2.4GHz 人体躯干遮挡方向增益模型 (-4.6dB 后向衰减)",
              style = MaterialTheme.typography.labelSmall,
              color = RadarAmber,
            )
            Switch(
              checked = bodyShadowingEnabled,
              onCheckedChange = onToggleBodyShadowing,
              modifier = Modifier.testTag("body_shadowing_switch"),
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "基准 TxPower (防127回退值): $defaultTxPower dBm",
              style = MaterialTheme.typography.labelSmall,
              color = RadarTextPrimary,
            )
            Text(
              text = "[-85 ~ -35 dBm]",
              style = MaterialTheme.typography.labelSmall,
              color = RadarTextSecondary,
            )
          }
          Slider(
            value = defaultTxPower.toFloat(),
            onValueChange = { onUpdateDefaultTxPower(it.roundToInt()) },
            valueRange = -85f..-35f,
            colors =
              SliderDefaults.colors(
                thumbColor = RadarCyan,
                activeTrackColor = RadarCyan,
              ),
            modifier = Modifier.testTag("tx_power_slider"),
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "环境路径损耗指数 n: ${basePathLossN.format2()}",
              style = MaterialTheme.typography.labelSmall,
              color = RadarTextPrimary,
            )
            Text(
              text = "[1.8 自由空间 ~ 3.8 多隔墙]",
              style = MaterialTheme.typography.labelSmall,
              color = RadarTextSecondary,
            )
          }
          Slider(
            value = basePathLossN,
            onValueChange = onUpdateBasePathLossN,
            valueRange = 1.8f..3.8f,
            colors =
              SliderDefaults.colors(
                thumbColor = RadarOrange,
                activeTrackColor = RadarOrange,
              ),
            modifier = Modifier.testTag("path_loss_slider"),
          )
        }
      }
    }
  }
}
