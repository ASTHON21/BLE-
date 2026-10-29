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
import android.os.ParcelUuid
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
    } catch (_: Throwable) {
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
      } catch (_: Throwable) {
        // Ignore if restricted by OEM ROM
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
      } catch (_: Throwable) {
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
      } catch (_: Throwable) {
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

        override fun onScanFailed(errorCode: Int) {
          // Keep flow alive or allow clean close without crashing
        }

        @SuppressLint("MissingPermission")
        private fun handleResult(result: ScanResult) {
          try {
            val device = result.device ?: return
            val mac = device.address ?: return
            val record = result.scanRecord

            val mfgSparse = record?.manufacturerSpecificData
            val serviceUuids = record?.serviceUuids
            val (companyId, vendorName) =
              resolveVendorWithServiceFallback(mfgSparse, serviceUuids)
            val mfgBytes = BlePacketDissector.extractFirstManufacturerBytes(mfgSparse)

            val rawName =
              try {
                record?.deviceName?.takeIf { it.isNotBlank() }
                  ?: device.name?.takeIf { it.isNotBlank() }
              } catch (_: Throwable) {
                record?.deviceName?.takeIf { it.isNotBlank() }
              }

            val friendlyName =
              rawName
                ?: if (vendorName != "Generic BLE") {
                  "$vendorName 信标 [${mac.takeLast(5)}]"
                } else {
                  "BLE 信标 [${mac.takeLast(5)}]"
                }

            val rawTxPower =
              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                result.txPower
              } else {
                record?.txPowerLevel ?: SignalFilterEngine.TX_POWER_NOT_PRESENT
              }

            trySend(
              RawBleAdvertisement(
                macAddress = mac,
                deviceName = friendlyName,
                manufacturerId = companyId,
                vendorName = vendorName,
                manufacturerBytes = mfgBytes,
                rawRssi = result.rssi,
                rawTxPower = rawTxPower,
                timestampMs = System.currentTimeMillis(),
              )
            )
          } catch (_: Throwable) {
            // Never crash on malformed real-world BLE advertisement packets
          }
        }
      }

    var started = false
    try {
      lastScanStartElapsedMs = SystemClock.elapsedRealtime()
      scanner.startScan(null, settingsBuilder.build(), scanCallback)
      started = true
    } catch (_: Throwable) {
      started = false
    }

    awaitClose {
      if (started) {
        try {
          scanner.stopScan(scanCallback)
        } catch (_: Throwable) {
          // Ignore when Bluetooth or permission is revoked mid-scan
        }
      }
    }
  }

  private fun resolveVendorWithServiceFallback(
    mfgSparse: android.util.SparseArray<ByteArray>?,
    serviceUuids: List<ParcelUuid>?,
  ): Pair<Int?, String> {
    val (companyId, vendorName) = SignalFilterEngine.resolveVendor(mfgSparse)
    if (companyId != null) return companyId to vendorName

    if (!serviceUuids.isNullOrEmpty()) {
      for (parcelUuid in serviceUuids) {
        val uuidStr = parcelUuid.uuid.toString().lowercase()
        when {
          uuidStr.startsWith("0000fe95") -> return 0x038F to "Xiaomi"
          uuidStr.startsWith("0000feaa") || uuidStr.startsWith("0000fe2c") ->
            return 0x00E0 to "Google"
          uuidStr.startsWith("0000fd5a") || uuidStr.startsWith("0000fd59") ->
            return 0x0075 to "Samsung"
          uuidStr.startsWith("0000fd6f") -> return 0x004C to "Apple/Google EN"
          uuidStr.startsWith("00001812") -> return null to "BLE HID 键鼠/触控笔"
          uuidStr.startsWith("0000180d") -> return null to "BLE 心率手环"
          uuidStr.startsWith("0000180f") -> return null to "BLE 电池外设"
        }
      }
    }
    return null to "Generic BLE"
  }
}
