package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.HardwareCapabilities
import com.example.ui.BlackboxScreen
import com.example.ui.ScannerScreen
import com.example.ui.TrackerScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.RadarAmber
import com.example.ui.theme.RadarBgCard
import com.example.ui.theme.RadarBgDeep
import com.example.ui.theme.RadarBgSurface
import com.example.ui.theme.RadarBorder
import com.example.ui.theme.RadarCrimson
import com.example.ui.theme.RadarCyan
import com.example.ui.theme.RadarEmerald
import com.example.ui.theme.RadarOrange
import com.example.ui.theme.RadarTextPrimary
import com.example.ui.theme.RadarTextSecondary
import com.example.viewmodel.RadarTab
import com.example.viewmodel.RadarViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      MyApplicationTheme {
        BleParticleRadarApp()
      }
    }
  }
}

@Composable
fun BleParticleRadarApp(radarViewModel: RadarViewModel = viewModel()) {
  val activeTab by radarViewModel.activeTab.collectAsStateWithLifecycle()
  val bodyShadowingEnabled by radarViewModel.bodyShadowingEnabled.collectAsStateWithLifecycle()
  val capabilities by radarViewModel.hardwareCapabilities.collectAsStateWithLifecycle()
  val devices by radarViewModel.devices.collectAsStateWithLifecycle()
  val oscilloscopeTrace by radarViewModel.oscilloscopeTrace.collectAsStateWithLifecycle()
  val lockedMac by radarViewModel.lockedMac.collectAsStateWithLifecycle()
  val pdrState by radarViewModel.pdrState.collectAsStateWithLifecycle()
  val particleSnapshot by radarViewModel.particleSnapshot.collectAsStateWithLifecycle()
  val defaultTxPower by radarViewModel.defaultTxPower.collectAsStateWithLifecycle()
  val basePathLossN by radarViewModel.basePathLossN.collectAsStateWithLifecycle()
  val geigerEnabled by radarViewModel.geigerEnabled.collectAsStateWithLifecycle()
  val calibratedBeacons by radarViewModel.calibratedBeacons.collectAsStateWithLifecycle()
  val huntSessions by radarViewModel.huntSessions.collectAsStateWithLifecycle()

  val permissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
      radarViewModel.refreshHardwareCapabilities()
    }

  LaunchedEffect(Unit) {
    if (!capabilities.hasScanPermission) {
      permissionLauncher.launch(radarViewModel.requiredPermissions())
    }
  }

  val lockedDevice = devices.firstOrNull { it.macAddress == lockedMac } ?: devices.firstOrNull()

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = RadarBgDeep,
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopHardwareTelemetryHeader(
        capabilities = capabilities,
        activeDeviceCount = devices.size,
        onRescan = { radarViewModel.clearAndRescanDevices() },
      )
    },
    bottomBar = {
      NavigationBar(
        containerColor = RadarBgSurface,
        tonalElevation = 8.dp,
      ) {
        NavigationBarItem(
          selected = activeTab == RadarTab.SCANNER,
          onClick = { radarViewModel.selectTab(RadarTab.SCANNER) },
          icon = {
            Icon(
              imageVector = Icons.Default.Radar,
              contentDescription = "广域声呐与示波台",
            )
          },
          label = {
            Text(
              text = "L1-L2 声呐示波台",
              style = MaterialTheme.typography.labelSmall,
            )
          },
          colors =
            NavigationBarItemDefaults.colors(
              selectedIconColor = Color.Black,
              selectedTextColor = RadarCyan,
              indicatorColor = RadarCyan,
              unselectedIconColor = RadarTextSecondary,
              unselectedTextColor = RadarTextSecondary,
            ),
          modifier = Modifier.testTag("nav_tab_scanner"),
        )
        NavigationBarItem(
          selected = activeTab == RadarTab.TRACKER,
          onClick = { radarViewModel.selectTab(RadarTab.TRACKER) },
          icon = {
            Icon(
              imageVector = Icons.Default.GpsFixed,
              contentDescription = "L4+ 粒子坍缩追踪舱",
            )
          },
          label = {
            Text(
              text = "L4+ 粒子追踪舱",
              style = MaterialTheme.typography.labelSmall,
            )
          },
          colors =
            NavigationBarItemDefaults.colors(
              selectedIconColor = Color.Black,
              selectedTextColor = RadarOrange,
              indicatorColor = RadarOrange,
              unselectedIconColor = RadarTextSecondary,
              unselectedTextColor = RadarTextSecondary,
            ),
          modifier = Modifier.testTag("nav_tab_tracker"),
        )
        NavigationBarItem(
          selected = activeTab == RadarTab.BLACKBOX,
          onClick = { radarViewModel.selectTab(RadarTab.BLACKBOX) },
          icon = {
            Icon(
              imageVector = Icons.Default.Memory,
              contentDescription = "协议拆包与校准黑匣子",
            )
          },
          label = {
            Text(
              text = "协议与Room黑匣子",
              style = MaterialTheme.typography.labelSmall,
            )
          },
          colors =
            NavigationBarItemDefaults.colors(
              selectedIconColor = Color.Black,
              selectedTextColor = RadarAmber,
              indicatorColor = RadarAmber,
              unselectedIconColor = RadarTextSecondary,
              unselectedTextColor = RadarTextSecondary,
            ),
          modifier = Modifier.testTag("nav_tab_blackbox"),
        )
      }
    },
  ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      when (activeTab) {
        RadarTab.SCANNER ->
          ScannerScreen(
            capabilities = capabilities,
            devices = devices,
            lockedMac = lockedDevice?.macAddress,
            oscilloscopeTrace = oscilloscopeTrace,
            onRequestPermissions = {
              permissionLauncher.launch(radarViewModel.requiredPermissions())
            },
            onLockDevice = { mac -> radarViewModel.lockOnDevice(mac) },
            onClearAndRescan = { radarViewModel.clearAndRescanDevices() },
          )

        RadarTab.TRACKER ->
          TrackerScreen(
            bodyShadowingEnabled = bodyShadowingEnabled,
            lockedDevice = lockedDevice,
            pdrState = pdrState,
            particleSnapshot = particleSnapshot,
            defaultTxPower = defaultTxPower,
            basePathLossN = basePathLossN,
            geigerEnabled = geigerEnabled,
            onToggleBodyShadowing = { enabled ->
              radarViewModel.setBodyShadowingEnabled(enabled)
            },
            onStepDirection = { dx, dy -> radarViewModel.stepVirtualDirection(dx, dy) },
            onRotateHeading = { deg -> radarViewModel.rotateVirtualHeading(deg) },
            onResetParticles = { radarViewModel.resetParticleCloud() },
            onRecordHuntSession = { radarViewModel.recordCurrentHuntSession() },
            onToggleGeiger = { enabled -> radarViewModel.setGeigerEnabled(enabled) },
            onUpdateDefaultTxPower = { tx -> radarViewModel.updateDefaultTxPower(tx) },
            onUpdateBasePathLossN = { n -> radarViewModel.updateBasePathLossN(n) },
            onNavigateBackToScanner = { radarViewModel.selectTab(RadarTab.SCANNER) },
          )

        RadarTab.BLACKBOX ->
          BlackboxScreen(
            lockedDevice = lockedDevice,
            defaultTxPower = defaultTxPower,
            basePathLossN = basePathLossN,
            calibratedBeacons = calibratedBeacons,
            huntSessions = huntSessions,
            onSaveCalibration = { alias -> radarViewModel.saveDeviceCalibration(alias) },
            onDeleteCalibration = { mac -> radarViewModel.deleteDeviceCalibration(mac) },
            onRecordHuntSession = { radarViewModel.recordCurrentHuntSession() },
            onDeleteHuntSession = { id -> radarViewModel.deleteHuntSession(id) },
            onClearAllHuntSessions = { radarViewModel.clearAllHuntSessions() },
            onNavigateBack = { radarViewModel.selectTab(RadarTab.TRACKER) },
          )
      }
    }
  }
}

@Composable
private fun TopHardwareTelemetryHeader(
  capabilities: HardwareCapabilities,
  activeDeviceCount: Int,
  onRescan: () -> Unit,
) {
  val isHardwareReady = capabilities.hasScanPermission && capabilities.isBluetoothEnabled
  Surface(
    color = RadarBgSurface,
    border = BorderStroke(1.dp, RadarBorder),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "BLE 空间概率粒子寻物雷达",
          style = MaterialTheme.typography.headlineSmall,
          color = RadarTextPrimary,
        )
        Text(
          text = "L4+ 双峰破缺 · 人体遮挡 · 零GC双缓冲 · SoA N=2500",
          style = MaterialTheme.typography.labelSmall,
          color = RadarCyan,
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Surface(
          color =
            if (isHardwareReady) RadarEmerald.copy(alpha = 0.16f)
            else RadarCrimson.copy(alpha = 0.18f),
          shape = RoundedCornerShape(8.dp),
          border =
            BorderStroke(
              1.dp,
              if (isHardwareReady) RadarEmerald.copy(alpha = 0.65f) else RadarCrimson,
            ),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier =
                Modifier.size(7.dp)
                  .clip(CircleShape)
                  .background(if (isHardwareReady) RadarEmerald else RadarCrimson)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
              text =
                if (isHardwareReady) "2.4GHz 实时 (${activeDeviceCount})"
                else "硬件待就绪",
              style = MaterialTheme.typography.labelSmall,
              color = if (isHardwareReady) RadarEmerald else RadarCrimson,
              fontWeight = FontWeight.Bold,
            )
          }
        }

        Surface(
          modifier =
            Modifier.clip(RoundedCornerShape(8.dp))
              .clickable { onRescan() }
              .testTag("top_rescan_button"),
          color = RadarBgCard,
          border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.6f)),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = "重新扫描",
              tint = RadarCyan,
              modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "重扫",
              style = MaterialTheme.typography.labelSmall,
              color = RadarCyan,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }
}
