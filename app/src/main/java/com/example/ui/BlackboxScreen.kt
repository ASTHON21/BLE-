package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.CalibratedBeaconEntity
import com.example.data.RadarHuntSessionEntity
import com.example.model.BleDeviceTelemetry
import com.example.ui.theme.RadarAmber
import com.example.ui.theme.RadarBgCard
import com.example.ui.theme.RadarBgDeep
import com.example.ui.theme.RadarBgElevated
import com.example.ui.theme.RadarBorder
import com.example.ui.theme.RadarCrimson
import com.example.ui.theme.RadarCyan
import com.example.ui.theme.RadarEmerald
import com.example.ui.theme.RadarOrange
import com.example.ui.theme.RadarTextPrimary
import com.example.ui.theme.RadarTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tab 3: BLE Protocol Hex Dissector & Room Device Calibration / Hunt Blackbox Screen.
 */
@Composable
fun BlackboxScreen(
  lockedDevice: BleDeviceTelemetry?,
  defaultTxPower: Int,
  basePathLossN: Float,
  calibratedBeacons: List<CalibratedBeaconEntity>,
  huntSessions: List<RadarHuntSessionEntity>,
  onSaveCalibration: (customAlias: String) -> Unit,
  onDeleteCalibration: (macAddress: String) -> Unit,
  onRecordHuntSession: () -> Unit,
  onDeleteHuntSession: (id: Long) -> Unit,
  onClearAllHuntSessions: () -> Unit,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onNavigateBack)

  var aliasInput by
    remember(lockedDevice?.macAddress) {
      mutableStateOf(lockedDevice?.deviceName ?: "")
    }

  LazyColumn(
    modifier = modifier.fillMaxSize().padding(horizontal = 12.dp).testTag("blackbox_screen_list"),
    verticalArrangement = Arrangement.spacedBy(10.dp),
    contentPadding = PaddingValues(vertical = 8.dp),
  ) {
    // 1. Live BLE Protocol AD Frame Dissector Card
    item {
      ProtocolDissectorCard(lockedDevice = lockedDevice)
    }

    // 2. Room Per-Device Calibration Profile Editor Card
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = RadarBgCard),
        border = BorderStroke(1.dp, RadarBorder),
        shape = RoundedCornerShape(12.dp),
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Text(
            text = "Room 射频校准档案库 (${calibratedBeacons.size} 台已校准)",
            style = MaterialTheme.typography.titleMedium,
            color = RadarCyan,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text =
              "当前绑定信标: ${lockedDevice?.macAddress ?: "无"} · TxPower=$defaultTxPower dBm · n=${basePathLossN.format2()}",
            style = MaterialTheme.typography.labelSmall,
            color = RadarTextSecondary,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedTextField(
              value = aliasInput,
              onValueChange = { aliasInput = it },
              label = { Text("自定义信标战术呼号", style = MaterialTheme.typography.labelSmall) },
              singleLine = true,
              modifier = Modifier.weight(1f).testTag("calibration_alias_input"),
            )
            Button(
              onClick = { onSaveCalibration(aliasInput) },
              colors = ButtonDefaults.buttonColors(containerColor = RadarCyan),
              modifier = Modifier.testTag("save_calibration_button"),
            ) {
              Icon(
                imageVector = Icons.Default.Save,
                contentDescription = "Save Calibration",
                tint = Color.Black,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "保存校准",
                style = MaterialTheme.typography.labelMedium,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
              )
            }
          }

          if (calibratedBeacons.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            for (cal in calibratedBeacons) {
              Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                color = RadarBgElevated,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, RadarBorder),
              ) {
                Row(
                  modifier =
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "${cal.customAlias} (${cal.vendorName})",
                      style = MaterialTheme.typography.labelMedium,
                      color = RadarTextPrimary,
                    )
                    Text(
                      text =
                        "MAC: ${cal.macAddress} | Tx: ${cal.calibratedTxPower}dBm | n=${cal.calibratedPathLossN.format2()}",
                      style = MaterialTheme.typography.labelSmall,
                      color = RadarEmerald,
                    )
                  }
                  IconButton(
                    onClick = { onDeleteCalibration(cal.macAddress) },
                    modifier = Modifier.size(36.dp),
                  ) {
                    Icon(
                      imageVector = Icons.Default.DeleteOutline,
                      contentDescription = "Delete calibration",
                      tint = RadarCrimson,
                      modifier = Modifier.size(18.dp),
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

    // 3. Room Radar Hunt Blackbox Sessions Header
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.History,
            contentDescription = "Hunt Logs",
            tint = RadarOrange,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "寻物定位黑匣子复盘日志 (${huntSessions.size})",
            style = MaterialTheme.typography.titleMedium,
            color = RadarTextPrimary,
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Button(
            onClick = onRecordHuntSession,
            colors = ButtonDefaults.buttonColors(containerColor = RadarEmerald),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            modifier = Modifier.testTag("blackbox_record_session_button"),
          ) {
            Icon(
              imageVector = Icons.Default.BookmarkAdded,
              contentDescription = "Record snapshot",
              tint = Color.Black,
              modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "记录当前锁定快照",
              style = MaterialTheme.typography.labelSmall,
              color = Color.Black,
              fontWeight = FontWeight.Bold,
            )
          }
          if (huntSessions.isNotEmpty()) {
            OutlinedButton(
              onClick = onClearAllHuntSessions,
              border = BorderStroke(1.dp, RadarCrimson.copy(alpha = 0.6f)),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            ) {
              Text(
                text = "清空",
                style = MaterialTheme.typography.labelSmall,
                color = RadarCrimson,
              )
            }
          }
        }
      }
    }

    if (huntSessions.isEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = RadarBgCard),
          border = BorderStroke(1.dp, RadarBorder),
          shape = RoundedCornerShape(12.dp),
        ) {
          Text(
            text = "暂无寻物复盘记录。点击上方「记录当前锁定快照」或在追踪舱点击「存入黑匣子」即可将收敛结果持久化至本地 Room 数据库。",
            style = MaterialTheme.typography.bodyMedium,
            color = RadarTextSecondary,
            modifier = Modifier.padding(16.dp),
          )
        }
      }
    } else {
      items(items = huntSessions, key = { it.id }) { session ->
        HuntSessionCard(
          session = session,
          onDelete = { onDeleteHuntSession(session.id) },
        )
      }
    }
  }
}

@Composable
private fun ProtocolDissectorCard(lockedDevice: BleDeviceTelemetry?) {
  val dissected = lockedDevice?.dissectedProtocol
  Card(
    modifier = Modifier.fillMaxWidth().testTag("protocol_dissector_card"),
    colors = CardDefaults.cardColors(containerColor = RadarBgCard),
    border = BorderStroke(1.dp, RadarBorder),
    shape = RoundedCornerShape(12.dp),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Default.Code,
            contentDescription = "BLE Packet Dissector",
            tint = RadarAmber,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "BLE 广播帧协议深度拆包器",
            style = MaterialTheme.typography.titleMedium,
            color = RadarAmber,
          )
        }
        Text(
          text = lockedDevice?.vendorName ?: "N/A",
          style = MaterialTheme.typography.labelSmall,
          color = RadarCyan,
        )
      }

      Spacer(modifier = Modifier.height(6.dp))

      if (lockedDevice == null || dissected == null) {
        Text(
          text = "等待选定信标以解析原始 GAP AD Structure 字节流...",
          style = MaterialTheme.typography.bodyMedium,
          color = RadarTextSecondary,
        )
      } else {
        Text(
          text = dissected.protocolStandard,
          style = MaterialTheme.typography.labelMedium,
          color = RadarTextPrimary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(8.dp))
              .background(RadarBgDeep)
              .padding(8.dp)
        ) {
          Text(
            text = "HEX: ${dissected.payloadHex}",
            style = MaterialTheme.typography.labelSmall,
            color = RadarEmerald,
          )
        }
        Spacer(modifier = Modifier.height(8.dp))
        for ((k, v) in dissected.fields) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = k,
              style = MaterialTheme.typography.labelSmall,
              color = RadarTextSecondary,
            )
            Text(
              text = v,
              style = MaterialTheme.typography.labelSmall,
              color = RadarTextPrimary,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun HuntSessionCard(
  session: RadarHuntSessionEntity,
  onDelete: () -> Unit,
) {
  val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.US) }
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = RadarBgCard),
    border = BorderStroke(1.dp, RadarBorder),
    shape = RoundedCornerShape(10.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "${session.deviceName} (${session.vendorName})",
          style = MaterialTheme.typography.titleMedium,
          color = RadarTextPrimary,
        )
        Text(
          text =
            "锁定坐标: (${session.finalEstX.format2()}m, ${session.finalEstY.format2()}m) · 距离 ${session.finalDistM.format2()}m · 置信度 ${session.confidencePct.format1()}%",
          style = MaterialTheme.typography.labelSmall,
          color = RadarOrange,
        )
        Text(
          text =
            "${session.obstaclePresetName} | 迈步 ${session.stepsTaken} 次 | 重采样 ${session.resampleCount} 次 | ${timeFormat.format(Date(session.timestampMs))}",
          style = MaterialTheme.typography.labelSmall,
          color = RadarTextSecondary,
        )
      }
      IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
        Icon(
          imageVector = Icons.Default.DeleteOutline,
          contentDescription = "Delete session",
          tint = RadarCrimson,
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}
