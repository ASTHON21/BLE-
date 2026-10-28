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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.ui.BlackboxScreen
import com.example.ui.ScannerScreen
import com.example.ui.TrackerScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.RadarAmber
import com.example.ui.theme.RadarBgCard
import com.example.ui.theme.RadarBgDeep
import com.example.ui.theme.RadarBgSurface
import com.example.ui.theme.RadarBorder
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
  val isSandboxMode by radarViewModel.isSandboxMode.collectAsStateWithLifecycle()
  val nlosPreset by radarViewModel.nlosPreset.collectAsStateWithLifecycle()
  val obstacles by radarViewModel.obstacles.collectAsStateWithLifecycle()
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

  val lockedDevice = devices.firstOrNull { it.macAddress == lockedMac } ?: devices.firstOrNull()

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    containerColor = RadarBgDeep,
    contentWindowInsets = WindowInsets.safeDrawing,
    topBar = {
      TopDualModeHeader(
        isSandboxMode = isSandboxMode,
        onToggleMode = { targetSandbox ->
          radarViewModel.setSandboxMode(targetSandbox)
          if (!targetSandbox && !capabilities.hasScanPermission) {
            permissionLauncher.launch(radarViewModel.requiredPermissions())
          }
        },
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
            isSandboxMode = isSandboxMode,
            capabilities = capabilities,
            devices = devices,
            lockedMac = lockedDevice?.macAddress,
            oscilloscopeTrace = oscilloscopeTrace,
            onRequestPermissions = {
              permissionLauncher.launch(radarViewModel.requiredPermissions())
            },
            onLockDevice = { mac -> radarViewModel.lockOnDevice(mac) },
            onRandomizeSandbox = { radarViewModel.randomizeSandboxTargetPositions() },
          )

        RadarTab.TRACKER ->
          TrackerScreen(
            isSandboxMode = isSandboxMode,
            nlosPreset = nlosPreset,
            obstacles = obstacles,
            bodyShadowingEnabled = bodyShadowingEnabled,
            lockedDevice = lockedDevice,
            pdrState = pdrState,
            particleSnapshot = particleSnapshot,
            defaultTxPower = defaultTxPower,
            basePathLossN = basePathLossN,
            geigerEnabled = geigerEnabled,
            onSelectNlosPreset = { preset -> radarViewModel.selectNlosPreset(preset) },
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

/**
 * Contract 4: Sticky Top Dual-Mode Switcher [真机硬件雷达 / 物理仿真沙盒]
 */
@Composable
private fun TopDualModeHeader(
  isSandboxMode: Boolean,
  onToggleMode: (Boolean) -> Unit,
) {
  Surface(
    color = RadarBgSurface,
    border = BorderStroke(1.dp, RadarBorder),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
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
      }

      Spacer(modifier = Modifier.height(6.dp))

      Row(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(RadarBgCard)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        val sandboxSelected = isSandboxMode
        Box(
          modifier =
            Modifier.weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .background(if (sandboxSelected) RadarEmerald else Color.Transparent)
              .clickable { onToggleMode(true) }
              .padding(vertical = 8.dp)
              .testTag("mode_sandbox_button"),
          contentAlignment = Alignment.Center,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.Science,
              contentDescription = "物理仿真沙盒",
              tint = if (sandboxSelected) Color.Black else RadarTextSecondary,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
              text = "物理仿真沙盒 (5Hz+NLOS)",
              style = MaterialTheme.typography.labelMedium,
              color = if (sandboxSelected) Color.Black else RadarTextSecondary,
              fontWeight = if (sandboxSelected) FontWeight.Bold else FontWeight.Medium,
            )
          }
        }

        val hwSelected = !isSandboxMode
        Box(
          modifier =
            Modifier.weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .background(if (hwSelected) RadarCyan else Color.Transparent)
              .clickable { onToggleMode(false) }
              .padding(vertical = 8.dp)
              .testTag("mode_hardware_button"),
          contentAlignment = Alignment.Center,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Default.Bluetooth,
              contentDescription = "真机硬件雷达",
              tint = if (hwSelected) Color.Black else RadarTextSecondary,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
              text = "真机硬件雷达 (BLE)",
              style = MaterialTheme.typography.labelMedium,
              color = if (hwSelected) Color.Black else RadarTextSecondary,
              fontWeight = if (hwSelected) FontWeight.Bold else FontWeight.Medium,
            )
          }
        }
      }
    }
  }
}
