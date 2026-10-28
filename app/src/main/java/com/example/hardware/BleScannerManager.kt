package com.example.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.engine.BlePacketDissector
import com.example.engine.SignalFilterEngine
import com.example.model.HardwareCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class RawBleAdvertisement(
  val macAddress: String,
  val deviceName: String,
  val manufacturerId: Int?,
  val vendorName: String,
  val manufacturerBytes: ByteArray? = null,
  val rawRssi: Int,
  val rawTxPower: Int,
  val timestampMs: Long,
)

/**
 * Contract 1: Android 12~17 BLE Hardware Full-Capabilities Scanner with Anti-Throttling Cooldown
 * State Machine (5000ms lock).
 */
class BleScannerManager(private val context: Context) {

  companion object {
    const val MIN_SCAN_COOLDOWN_MS = 5000L
  }

  private val bluetoothManager: BluetoothManager? =
    context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

  @Volatile private var lastScanStartElapsedMs: Long = 0L

  /**
   * Permissions requested in the runtime dialog (includes ACTIVITY_RECOGNITION on Android 10+ so
   * hardware TYPE_STEP_DETECTOR works on real phones, and ACCESS_FINE_LOCATION so Android 12+ does
   * not filter out iBeacons/AirTags).
   */
  fun requiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACTIVITY_RECOGNITION,
      )
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACTIVITY_RECOGNITION,
      )
    } else {
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
  }

  /**
   * Checks whether core BLE scan permissions are granted (does not fail if optional
   * ACTIVITY_RECOGNITION is denied, since PdrSensorEngine automatically falls back to accelerometer).
   */
  fun hasRequiredPermissions(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
          PackageManager.PERMISSION_GRANTED
    } else {
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
          PackageManager.PERMISSION_GRANTED
    }
  }

  fun isBluetoothEnabled(): Boolean {
    return try {
      bluetoothAdapter?.isEnabled == true
    } catch (_: SecurityException) {
      false
    }
  }

  fun queryCapabilities(
    stepTier: String,
    orientationTier: String,
  ): HardwareCapabilities {
    val adapter = bluetoothAdapter
    val hasPerm = hasRequiredPermissions()
    val enabled = isBluetoothEnabled()

    var le2M = false
    var leCoded = false
    var extAdv = false
    var offloaded = false

    if (adapter != null && enabled) {
      try {
        offloaded = adapter.isOffloadedFilteringSupported
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          le2M = adapter.isLe2MPhySupported
          leCoded = adapter.isLeCodedPhySupported
          extAdv = adapter.isLeExtendedAdvertisingSupported
        }
      } catch (_: SecurityException) {
        // Ignore if restricted by OS
      }
    }

    val elapsedSinceLastStart = SystemClock.elapsedRealtime() - lastScanStartElapsedMs
    val cooldownRemaining =
      if (lastScanStartElapsedMs == 0L) 0L
      else (MIN_SCAN_COOLDOWN_MS - elapsedSinceLastStart).coerceAtLeast(0L)

    return HardwareCapabilities(
      isBluetoothSupported = adapter != null,
      isBluetoothEnabled = enabled,
      hasScanPermission = hasPerm,
      isLe2MPhySupported = le2M,
      isLeCodedPhySupported = leCoded,
      isLeExtendedAdvertisingSupported = extAdv,
      isOffloadedFilteringSupported = offloaded,
      stepSensorTier = stepTier,
      orientationSensorTier = orientationTier,
      scanCooldownRemainingMs = cooldownRemaining,
    )
  }

  /**
   * Contract 1.2: Single callbackFlow session with 5000ms minimum start/stop cooldown lock to
   * prevent Android 14+ 5-scans-per-30-seconds silent throttling.
   */
  @SuppressLint("MissingPermission")
  fun scanBleFlow(): Flow<RawBleAdvertisement> = callbackFlow {
    if (!hasRequiredPermissions() || !isBluetoothEnabled()) {
      close()
      return@callbackFlow
    }

    val nowElapsed = SystemClock.elapsedRealtime()
    val sinceLast = nowElapsed - lastScanStartElapsedMs
    if (lastScanStartElapsedMs > 0L && sinceLast < MIN_SCAN_COOLDOWN_MS) {
      val waitMs = MIN_SCAN_COOLDOWN_MS - sinceLast
      delay(waitMs)
    }

    if (!hasRequiredPermissions() || !isBluetoothEnabled()) {
      close()
      return@callbackFlow
    }

    val adapter = bluetoothAdapter
    val scanner: BluetoothLeScanner? =
      try {
        adapter?.bluetoothLeScanner
      } catch (_: SecurityException) {
        null
      }

    if (adapter == null || scanner == null) {
      close()
      return@callbackFlow
    }

    val settingsBuilder =
      ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      try {
        if (adapter.isLeExtendedAdvertisingSupported) {
          settingsBuilder.setLegacy(false)
          settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }
      } catch (_: SecurityException) {
        // Fallback to standard low latency settings
      }
    }

    val scanCallback =
      object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
          result ?: return
          handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
          results?.forEach { handleResult(it) }
        }

        @SuppressLint("MissingPermission")
        private fun handleResult(result: ScanResult) {
          val device = result.device ?: return
          val mac = device.address ?: return
          val record = result.scanRecord
          val name =
            try {
              record?.deviceName?.takeIf { it.isNotBlank() }
                ?: device.name?.takeIf { it.isNotBlank() }
                ?: "BLE-${mac.takeLast(5)}"
            } catch (_: SecurityException) {
              record?.deviceName?.takeIf { it.isNotBlank() } ?: "BLE-${mac.takeLast(5)}"
            }

          val mfgSparse = record?.manufacturerSpecificData
          val (companyId, vendorName) = SignalFilterEngine.resolveVendor(mfgSparse)
          val mfgBytes = BlePacketDissector.extractFirstManufacturerBytes(mfgSparse)

          val rawTxPower =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              result.txPower
            } else {
              record?.txPowerLevel ?: SignalFilterEngine.TX_POWER_NOT_PRESENT
            }

          trySend(
            RawBleAdvertisement(
              macAddress = mac,
              deviceName = name,
              manufacturerId = companyId,
              vendorName = vendorName,
              manufacturerBytes = mfgBytes,
              rawRssi = result.rssi,
              rawTxPower = rawTxPower,
              timestampMs = System.currentTimeMillis(),
            )
          )
        }
      }

    var started = false
    try {
      lastScanStartElapsedMs = SystemClock.elapsedRealtime()
      scanner.startScan(null, settingsBuilder.build(), scanCallback)
      started = true
    } catch (_: SecurityException) {
      started = false
    } catch (_: IllegalStateException) {
      started = false
    }

    awaitClose {
      if (started) {
        try {
          scanner.stopScan(scanCallback)
        } catch (_: SecurityException) {
          // Ignore when Bluetooth or permission is revoked mid-scan
        } catch (_: IllegalStateException) {
          // Ignore when adapter turns off mid-scan
        }
      }
    }
  }
}
