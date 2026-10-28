package com.example.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.SignalFilterEngine
import com.example.model.BleDeviceTelemetry
import com.example.model.HardwareCapabilities
import com.example.model.OscilloscopeTrace
import com.example.ui.theme.RadarAmber
import com.example.ui.theme.RadarBgCard
import com.example.ui.theme.RadarBgElevated
import com.example.ui.theme.RadarBorder
import com.example.ui.theme.RadarCrimson
import com.example.ui.theme.RadarCyan
import com.example.ui.theme.RadarCyanDim
import com.example.ui.theme.RadarEmerald
import com.example.ui.theme.RadarOrange
import com.example.ui.theme.RadarPurple
import com.example.ui.theme.RadarTextPrimary
import com.example.ui.theme.RadarTextSecondary
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

@Composable
fun ScannerScreen(
  isSandboxMode: Boolean,
  capabilities: HardwareCapabilities,
  devices: List<BleDeviceTelemetry>,
  lockedMac: String?,
  oscilloscopeTrace: OscilloscopeTrace,
  onRequestPermissions: () -> Unit,
  onLockDevice: (String) -> Unit,
  onRandomizeSandbox: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lockedDevice = devices.firstOrNull { it.macAddress == lockedMac } ?: devices.firstOrNull()

  Column(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp)) {
    // 1. Hardware PHY Capability Badges Bar
    HardwareCapabilityStrip(
      capabilities = capabilities,
      isSandboxMode = isSandboxMode,
      onRandomizeSandbox = onRandomizeSandbox,
    )

    Spacer(modifier = Modifier.height(6.dp))

    // 2. Contract 1.1: Permission & Bluetooth Hardware Guard Card when in Hardware Mode
    if (!isSandboxMode && (!capabilities.hasScanPermission || !capabilities.isBluetoothEnabled)) {
      HardwareGuardCard(
        hasPermission = capabilities.hasScanPermission,
        isBluetoothEnabled = capabilities.isBluetoothEnabled,
        onRequestPermissions = onRequestPermissions,
        onOpenBluetoothSettings = {
          try {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
          } catch (_: Throwable) {}
        },
        onOpenAppDetails = {
          try {
            val intent =
              Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
              )
            context.startActivity(intent)
          } catch (_: Throwable) {}
        },
      )
      Spacer(modifier = Modifier.height(6.dp))
    }

    // 3. Rotating Logarithmic Sonar Dial (1m / 3m / 10m concentric rings)
    LogarithmicSonarCard(
      devices = devices,
      lockedMac = lockedMac,
      isSandboxMode = isSandboxMode,
    )

    Spacer(modifier = Modifier.height(6.dp))

    // 4. L2 Three-Trace Real-Time RF Oscilloscope
    RfOscilloscopeCard(
      lockedDevice = lockedDevice,
      trace = oscilloscopeTrace,
    )

    Spacer(modifier = Modifier.height(6.dp))

    // 5. Telemetry Header
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.BluetoothSearching,
          contentDescription = "Telemetry list",
          tint = RadarCyan,
          modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
          text = "L2 卡尔曼平滑遥测流 (${devices.size} 信标)",
          style = MaterialTheme.typography.titleMedium,
          color = RadarTextPrimary,
        )
      }
      Text(
        text = "点击信标锁定追踪",
        style = MaterialTheme.typography.labelSmall,
        color = RadarOrange,
      )
    }

    Spacer(modifier = Modifier.height(6.dp))

    // 6. High-Density Sorted Telemetry List
    if (devices.isEmpty()) {
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(RadarBgCard)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            imageVector = Icons.Default.Radar,
            contentDescription = "No BLE packets",
            tint = RadarCyanDim,
            modifier = Modifier.size(36.dp),
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text =
              if (isSandboxMode) "正在初始化 5Hz 物理仿真信标..."
              else "正在监听 2.4GHz BLE 广播信道 (LOW_LATENCY)...",
            style = MaterialTheme.typography.bodyMedium,
            color = RadarTextSecondary,
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxWidth().weight(1f).testTag("device_telemetry_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 14.dp),
      ) {
        items(items = devices, key = { it.macAddress }) { device ->
          DeviceTelemetryCard(
            device = device,
            isLocked = device.macAddress == lockedMac,
            onClick = { onLockDevice(device.macAddress) },
          )
        }
      }
    }
  }
}

@Composable
private fun HardwareCapabilityStrip(
  capabilities: HardwareCapabilities,
  isSandboxMode: Boolean,
  onRandomizeSandbox: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(vertical = 2.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    CapabilityBadge(label = "2M PHY", supported = capabilities.isLe2MPhySupported)
    CapabilityBadge(label = "CODED PHY", supported = capabilities.isLeCodedPhySupported)
    CapabilityBadge(label = "EXT ADV", supported = capabilities.isLeExtendedAdvertisingSupported)
    CapabilityBadge(label = "HW FILTER", supported = capabilities.isOffloadedFilteringSupported)
    CapabilityBadge(
      label = capabilities.stepSensorTier,
      supported = true,
      accentColor = RadarCyan,
    )
    if (capabilities.scanCooldownRemainingMs > 0L) {
      CapabilityBadge(
        label = "防熔断锁 ${(capabilities.scanCooldownRemainingMs / 1000f).format1()}s",
        supported = true,
        accentColor = RadarAmber,
      )
    }
    if (isSandboxMode) {
      Surface(
        modifier =
          Modifier.clip(RoundedCornerShape(6.dp))
            .clickable { onRandomizeSandbox() }
            .testTag("randomize_sandbox_button"),
        color = RadarBgElevated,
        border = BorderStroke(1.dp, RadarOrange.copy(alpha = 0.7f)),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = "Randomize sandbox coordinates",
            tint = RadarOrange,
            modifier = Modifier.size(12.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(
            text = "随机重置隐藏靶标坐标",
            style = MaterialTheme.typography.labelSmall,
            color = RadarOrange,
          )
        }
      }
    }
  }
}

@Composable
private fun CapabilityBadge(
  label: String,
  supported: Boolean,
  accentColor: Color = if (supported) RadarEmerald else RadarTextSecondary,
) {
  Surface(
    shape = RoundedCornerShape(6.dp),
    color = RadarBgCard,
    border =
      BorderStroke(
        width = 1.dp,
        color = if (supported) accentColor.copy(alpha = 0.55f) else RadarBorder,
      ),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier.size(6.dp)
            .clip(CircleShape)
            .background(if (supported) accentColor else RadarTextSecondary.copy(alpha = 0.4f))
      )
      Spacer(modifier = Modifier.width(5.dp))
      Text(
        text = "$label: ${if (supported) "ON" else "N/A"}",
        style = MaterialTheme.typography.labelSmall,
        color = if (supported) RadarTextPrimary else RadarTextSecondary,
      )
    }
  }
}

@Composable
private fun HardwareGuardCard(
  hasPermission: Boolean,
  isBluetoothEnabled: Boolean,
  onRequestPermissions: () -> Unit,
  onOpenBluetoothSettings: () -> Unit,
  onOpenAppDetails: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("hardware_guard_card"),
    colors = CardDefaults.cardColors(containerColor = Color(0xFF28121E)),
    border = BorderStroke(1.dp, RadarCrimson.copy(alpha = 0.8f)),
    shape = RoundedCornerShape(12.dp),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Default.WarningAmber,
          contentDescription = "Hardware permission or Bluetooth warning",
          tint = RadarAmber,
          modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text =
            when {
              !hasPermission -> "真机蓝牙扫描未授权 (防熔断保护已拦截直接调用)"
              !isBluetoothEnabled -> "系统蓝牙硬件未开启 (!BluetoothAdapter.isEnabled)"
              else -> "硬件就绪"
            },
          style = MaterialTheme.typography.titleMedium,
          color = RadarTextPrimary,
        )
      }
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text =
          "契约 1 防护：已自动识别 Android API 版本请求对应权限，严禁未授权直接调用 BluetoothLeScanner。",
        style = MaterialTheme.typography.bodyMedium,
        color = RadarTextSecondary,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (!hasPermission) {
          Button(
            onClick = onRequestPermissions,
            colors = ButtonDefaults.buttonColors(containerColor = RadarCyan),
            modifier = Modifier.weight(1f).testTag("grant_permission_button"),
          ) {
            Text("一键授权扫描", color = Color.Black, style = MaterialTheme.typography.labelMedium)
          }
        }
        if (!isBluetoothEnabled) {
          Button(
            onClick = onOpenBluetoothSettings,
            colors = ButtonDefaults.buttonColors(containerColor = RadarOrange),
            modifier = Modifier.weight(1f).testTag("open_bt_settings_button"),
          ) {
            Text("开启系统蓝牙", color = Color.Black, style = MaterialTheme.typography.labelMedium)
          }
        }
        OutlinedButton(
          onClick = onOpenAppDetails,
          border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.6f)),
          modifier = Modifier.testTag("open_app_settings_button"),
        ) {
          Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = "System Settings",
            tint = RadarCyan,
            modifier = Modifier.size(15.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text("系统设置", color = RadarCyan, style = MaterialTheme.typography.labelMedium)
        }
      }
    }
  }
}

@Composable
private fun LogarithmicSonarCard(
  devices: List<BleDeviceTelemetry>,
  lockedMac: String?,
  isSandboxMode: Boolean,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "sonarSweep")
  val sweepAngleDeg by
    infiniteTransition.animateFloat(
      initialValue = 0f,
      targetValue = 360f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 3600, easing = LinearEasing),
          repeatMode = RepeatMode.Restart,
        ),
      label = "sweepAngle",
    )
  val textMeasurer = rememberTextMeasurer()

  Card(
    modifier = Modifier.fillMaxWidth().height(168.dp),
    colors = CardDefaults.cardColors(containerColor = RadarBgCard),
    border = BorderStroke(1.dp, RadarBorder),
    shape = RoundedCornerShape(14.dp),
  ) {
    Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxRadius = (size.minDimension / 2f) - 8.dp.toPx()

        fun distToRadius(dMeters: Float): Float {
          val clamped = dMeters.coerceIn(0.2f, 12.0f)
          val norm = (log10(clamped * 2.5f) / log10(12.0f * 2.5f)).coerceIn(0.08f, 1.0f)
          return norm * maxRadius
        }

        val ringDistances = listOf(1f, 3f, 10f)
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

        drawLine(
          color = RadarBorder,
          start = Offset(cx - maxRadius, cy),
          end = Offset(cx + maxRadius, cy),
          strokeWidth = 1.dp.toPx(),
        )
        drawLine(
          color = RadarBorder,
          start = Offset(cx, cy - maxRadius),
          end = Offset(cx, cy + maxRadius),
          strokeWidth = 1.dp.toPx(),
        )

        for (ringMeter in ringDistances) {
          val r = distToRadius(ringMeter)
          drawCircle(
            color = RadarCyan.copy(alpha = 0.28f),
            radius = r,
            center = Offset(cx, cy),
            style = Stroke(width = 1.2.dp.toPx(), pathEffect = dashEffect),
          )
          val labelLayout =
            textMeasurer.measure(
              text = "${ringMeter.toInt()}m",
              style =
                TextStyle(
                  color = RadarCyan.copy(alpha = 0.75f),
                  fontSize = 9.sp,
                  fontFamily = FontFamily.Monospace,
                  fontWeight = FontWeight.Bold,
                ),
            )
          drawText(
            textLayoutResult = labelLayout,
            topLeft = Offset(cx + 4.dp.toPx(), cy - r + 2.dp.toPx()),
          )
        }

        drawArc(
          brush =
            Brush.sweepGradient(
              0.0f to Color.Transparent,
              0.82f to RadarCyan.copy(alpha = 0.03f),
              1.0f to RadarCyan.copy(alpha = 0.32f),
              center = Offset(cx, cy),
            ),
          startAngle = sweepAngleDeg - 65f,
          sweepAngle = 65f,
          useCenter = true,
          topLeft = Offset(cx - maxRadius, cy - maxRadius),
          size = Size(maxRadius * 2f, maxRadius * 2f),
        )

        val sweepRad = sweepAngleDeg * (PI.toFloat() / 180f)
        drawLine(
          color = RadarCyan.copy(alpha = 0.85f),
          start = Offset(cx, cy),
          end = Offset(cx + maxRadius * cos(sweepRad), cy + maxRadius * sin(sweepRad)),
          strokeWidth = 2.dp.toPx(),
        )

        drawCircle(color = RadarEmerald, radius = 4.dp.toPx(), center = Offset(cx, cy))

        for (dev in devices) {
          val r = distToRadius(dev.estimatedDistanceM)
          val bx = cx + r * sin(dev.sonarAngleRad)
          val by = cy - r * cos(dev.sonarAngleRad)
          val isTarget = dev.macAddress == lockedMac
          val blipColor =
            when {
              dev.isSignalStale -> RadarCrimson
              isTarget -> RadarOrange
              dev.isNlosOccluded -> RadarPurple
              else -> RadarCyan
            }

          if (isTarget) {
            drawCircle(
              color = RadarOrange.copy(alpha = 0.30f),
              radius = 10.dp.toPx(),
              center = Offset(bx, by),
            )
          }
          drawCircle(
            color = blipColor,
            radius = if (isTarget) 5.dp.toPx() else 3.8.dp.toPx(),
            center = Offset(bx, by),
          )
        }
      }

      Column(modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
        Text(
          text = if (isSandboxMode) "SONAR: 5Hz 物理多径沙盒" else "SONAR: BLE 硬件实时扫描",
          style = MaterialTheme.typography.labelSmall,
          color = RadarCyan,
        )
        Text(
          text = "对数同心圆: 1m / 3m / 10m",
          style = MaterialTheme.typography.labelSmall,
          color = RadarTextSecondary,
        )
      }

      Column(
        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
        horizontalAlignment = Alignment.End,
      ) {
        Text(
          text = "Q=0.08 | R=3.5 | Hampel 3×MAD",
          style = MaterialTheme.typography.labelSmall,
          color = RadarEmerald,
        )
      }
    }
  }
}

@Composable
private fun DeviceTelemetryCard(
  device: BleDeviceTelemetry,
  isLocked: Boolean,
  onClick: () -> Unit,
) {
  val borderColor =
    when {
      device.isSignalStale -> RadarCrimson.copy(alpha = 0.8f)
      isLocked -> RadarOrange
      else -> RadarBorder
    }

  Card(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .clickable(onClick = onClick)
        .testTag("device_card_${device.macAddress}"),
    colors =
      CardDefaults.cardColors(
        containerColor = if (isLocked) RadarBgElevated else RadarBgCard
      ),
    border = BorderStroke(if (isLocked) 1.5.dp else 1.dp, borderColor),
    shape = RoundedCornerShape(12.dp),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f),
        ) {
          Surface(
            color = RadarCyan.copy(alpha = 0.16f),
            shape = RoundedCornerShape(5.dp),
            border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.5f)),
          ) {
            Text(
              text = device.vendorName,
              style = MaterialTheme.typography.labelSmall,
              color = RadarCyan,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = device.deviceName,
            style = MaterialTheme.typography.titleMedium,
            color = RadarTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          if (device.isNlosOccluded) {
            Surface(
              color = RadarPurple.copy(alpha = 0.2f),
              shape = RoundedCornerShape(5.dp),
              border = BorderStroke(1.dp, RadarPurple),
            ) {
              Text(
                text = "NLOS -${device.wallLossDb.format1()}dB",
                style = MaterialTheme.typography.labelSmall,
                color = RadarPurple,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
              )
            }
          }
          if (device.isSignalStale) {
            Surface(
              color = RadarCrimson.copy(alpha = 0.2f),
              shape = RoundedCornerShape(5.dp),
              border = BorderStroke(1.dp, RadarCrimson),
            ) {
              Text(
                text = "SIGNAL_STALE",
                style = MaterialTheme.typography.labelSmall,
                color = RadarCrimson,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
              )
            }
          } else if (isLocked) {
            Surface(
              color = RadarOrange.copy(alpha = 0.2f),
              shape = RoundedCornerShape(5.dp),
              border = BorderStroke(1.dp, RadarOrange),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.Default.GpsFixed,
                  contentDescription = "Locked Target",
                  tint = RadarOrange,
                  modifier = Modifier.size(11.dp),
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                  text = "L4 锁定中",
                  style = MaterialTheme.typography.labelSmall,
                  color = RadarOrange,
                )
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(5.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text =
            "MAC: ${device.macAddress} · ${device.dissectedProtocol?.frameType ?: "BLE Adv"}",
          style = MaterialTheme.typography.labelSmall,
          color = RadarTextSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        val txFallbackUsed =
          device.rawTxPower == SignalFilterEngine.TX_POWER_NOT_PRESENT ||
            device.rawTxPower !in -100..-20
        Text(
          text =
            if (txFallbackUsed) {
              "Tx: ${device.effectiveTxPower}dBm (防127)"
            } else {
              "Tx: ${device.effectiveTxPower}dBm"
            },
          style = MaterialTheme.typography.labelSmall,
          color = if (txFallbackUsed) RadarAmber else RadarTextSecondary,
        )
      }

      Spacer(modifier = Modifier.height(6.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        TelemetryMetricPill(
          label = "原始 RSSI",
          value = "${device.rawRssi} dBm",
          valueColor = RadarTextSecondary,
        )
        TelemetryMetricPill(
          label = "卡尔曼平滑",
          value = "${device.smoothedRssi.format1()} dBm",
          valueColor = RadarCyan,
        )
        TelemetryMetricPill(
          label = "估算距离",
          value = "${device.estimatedDistanceM.format2()} m",
          valueColor = if (isLocked) RadarOrange else RadarEmerald,
        )
        TelemetryMetricPill(
          label = "收包间隔",
          value = "${device.packetIntervalMs} ms",
          valueColor = RadarTextPrimary,
        )
      }
    }
  }
}

@Composable
private fun TelemetryMetricPill(
  label: String,
  value: String,
  valueColor: Color,
) {
  Column(horizontalAlignment = Alignment.Start) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = RadarTextSecondary,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.labelMedium,
      color = valueColor,
      fontWeight = FontWeight.Bold,
    )
  }
}

internal fun Float.format1(): String = String.format(Locale.US, "%.1f", this)

internal fun Float.format2(): String = String.format(Locale.US, "%.2f", this)
