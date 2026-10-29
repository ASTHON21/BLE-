package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CalibratedBeaconEntity
import com.example.data.RadarDatabase
import com.example.data.RadarHuntSessionEntity
import com.example.data.RadarRepository
import com.example.engine.BlePacketDissector
import com.example.engine.MonteCarloParticleEngine
import com.example.engine.SignalFilterEngine
import com.example.hardware.BleScannerManager
import com.example.hardware.PdrSensorEngine
import com.example.hardware.RawBleAdvertisement
import com.example.model.BleDeviceTelemetry
import com.example.model.HardwareCapabilities
import com.example.model.OscilloscopeTrace
import com.example.model.ParticleSnapshot
import com.example.model.PdrState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class RadarTab {
  SCANNER,
  TRACKER,
  BLACKBOX,
}

class RadarViewModel(application: Application) : AndroidViewModel(application) {

  private val bleScannerManager = BleScannerManager(application.applicationContext)
  private val signalFilterEngine = SignalFilterEngine()
  private val particleEngine = MonteCarloParticleEngine()
  private val repository =
    RadarRepository(RadarDatabase.getInstance(application.applicationContext).radarDao())

  private val pdrSensorEngine =
    PdrSensorEngine(
      context = application.applicationContext,
      onAzimuthUpdated = { azimuthRad, smoothSin, smoothCos ->
        _pdrState.update { state ->
          state.copy(azimuthRad = azimuthRad, smoothSin = smoothSin, smoothCos = smoothCos)
        }
      },
      onHardwareStepDetected = { dynamicStepLen ->
        val currentAzimuth = _pdrState.value.azimuthRad
        val dx = dynamicStepLen * sin(currentAzimuth)
        val dy = dynamicStepLen * cos(currentAzimuth)
        applyUserDisplacement(dx = dx, dy = dy, measuredStepLength = dynamicStepLen)
      },
    )

  // Room Database Flows
  val calibratedBeacons: StateFlow<List<CalibratedBeaconEntity>> =
    repository.calibratedBeacons.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  val huntSessions: StateFlow<List<RadarHuntSessionEntity>> =
    repository.huntSessions.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  private val calibrationCache = ConcurrentHashMap<String, CalibratedBeaconEntity>()

  // UI StateFlows
  private val _activeTab = MutableStateFlow(RadarTab.SCANNER)
  val activeTab: StateFlow<RadarTab> = _activeTab.asStateFlow()

  private val _bodyShadowingEnabled = MutableStateFlow(true)
  val bodyShadowingEnabled: StateFlow<Boolean> = _bodyShadowingEnabled.asStateFlow()

  private val _hardwareCapabilities = MutableStateFlow(HardwareCapabilities())
  val hardwareCapabilities: StateFlow<HardwareCapabilities> = _hardwareCapabilities.asStateFlow()

  private val _devices = MutableStateFlow<List<BleDeviceTelemetry>>(emptyList())
  val devices: StateFlow<List<BleDeviceTelemetry>> = _devices.asStateFlow()

  private val _oscilloscopeTrace = MutableStateFlow(OscilloscopeTrace())
  val oscilloscopeTrace: StateFlow<OscilloscopeTrace> = _oscilloscopeTrace.asStateFlow()

  private val _lockedMac = MutableStateFlow<String?>(null)
  val lockedMac: StateFlow<String?> = _lockedMac.asStateFlow()

  private val _pdrState = MutableStateFlow(PdrState())
  val pdrState: StateFlow<PdrState> = _pdrState.asStateFlow()

  private val _defaultTxPower = MutableStateFlow(SignalFilterEngine.DEFAULT_TX_POWER_DBM)
  val defaultTxPower: StateFlow<Int> = _defaultTxPower.asStateFlow()

  private val _basePathLossN = MutableStateFlow(2.4f)
  val basePathLossN: StateFlow<Float> = _basePathLossN.asStateFlow()

  private val _geigerEnabled = MutableStateFlow(false)
  val geigerEnabled: StateFlow<Boolean> = _geigerEnabled.asStateFlow()

  private val _particleSnapshot =
    MutableStateFlow(
      particleEngine.currentSnapshot(userX = 0f, userY = 0f, userAzimuthRad = 0f)
    )
  val particleSnapshot: StateFlow<ParticleSnapshot> = _particleSnapshot.asStateFlow()

  private val deviceMap = ConcurrentHashMap<String, BleDeviceTelemetry>()
  @Volatile private var hardwareScanJob: Job? = null
  private val streamLock = Any()

  init {
    viewModelScope.launch {
      calibratedBeacons.collect { list ->
        calibrationCache.clear()
        list.forEach { calibrationCache[it.macAddress] = it }
      }
    }
    pdrSensorEngine.start()
    refreshHardwareCapabilities()
    startStalenessWatchdog()
  }

  fun requiredPermissions(): Array<String> = bleScannerManager.requiredPermissions()

  /**
   * Pure capability query that updates _hardwareCapabilities StateFlow WITHOUT recursive stream restarts.
   */
  private fun queryAndPublishCapabilities(): HardwareCapabilities {
    val caps =
      bleScannerManager.queryCapabilities(
        stepTier = pdrSensorEngine.stepSensorTierName,
        orientationTier = pdrSensorEngine.orientationSensorTierName,
      )
    _hardwareCapabilities.value = caps
    return caps
  }

  /**
   * Public capability refresh entry point: updates capabilities and starts hardware scan if needed
   * without any recursive loop.
   */
  fun refreshHardwareCapabilities() {
    val caps = queryAndPublishCapabilities()
    if (caps.hasScanPermission && caps.isBluetoothEnabled) {
      synchronized(streamLock) {
        if (hardwareScanJob?.isActive != true) {
          startHardwareBleScanLocked(caps)
        }
      }
    }
  }

  /**
   * Clears cached telemetry & filter buffers and restarts real BLE scanning.
   */
  fun clearAndRescanDevices() {
    deviceMap.clear()
    signalFilterEngine.clear()
    _lockedMac.value = null
    _devices.value = emptyList()
    _oscilloscopeTrace.value = OscilloscopeTrace()
    synchronized(streamLock) {
      hardwareScanJob?.cancel()
      hardwareScanJob = null
      val caps = queryAndPublishCapabilities()
      startHardwareBleScanLocked(caps)
    }
  }

  fun setBodyShadowingEnabled(enabled: Boolean) {
    _bodyShadowingEnabled.value = enabled
  }

  fun selectTab(tab: RadarTab) {
    _activeTab.value = tab
  }

  fun lockOnDevice(macAddress: String) {
    _lockedMac.value = macAddress
    calibrationCache[macAddress]?.let { cal ->
      _defaultTxPower.value = cal.calibratedTxPower
      _basePathLossN.value = cal.calibratedPathLossN
    }
    _pdrState.update { state ->
      state.copy(
        userX = 0f,
        userY = 0f,
        stepCount = 0,
        trajectory = listOf(0f to 0f),
      )
    }
    val targetDev = deviceMap[macAddress]
    val initialDistHint = targetDev?.estimatedDistanceM ?: 4.5f
    val baseN = _basePathLossN.value
    _oscilloscopeTrace.value = signalFilterEngine.getOscilloscopeTrace(macAddress)
    viewModelScope.launch(Dispatchers.Default) {
      particleEngine.resetParticles(
        centerX = 0f,
        centerY = 0f,
        initialRadiusHint = initialDistHint,
        basePathLossN = baseN,
      )
      val snap =
        particleEngine.currentSnapshot(
          userX = 0f,
          userY = 0f,
          userAzimuthRad = _pdrState.value.azimuthRad,
        )
      _particleSnapshot.value = snap
    }
    _activeTab.value = RadarTab.TRACKER
  }

  fun resetParticleCloud() {
    val pdr = _pdrState.value
    val locked = _lockedMac.value?.let { deviceMap[it] }
    val distHint = locked?.estimatedDistanceM ?: 4.5f
    val baseN = _basePathLossN.value
    viewModelScope.launch(Dispatchers.Default) {
      particleEngine.resetParticles(
        centerX = pdr.userX,
        centerY = pdr.userY,
        initialRadiusHint = distHint,
        basePathLossN = baseN,
      )
      _particleSnapshot.value =
        particleEngine.currentSnapshot(
          userX = pdr.userX,
          userY = pdr.userY,
          userAzimuthRad = pdr.azimuthRad,
        )
    }
  }

  fun stepVirtualDirection(dxNormalized: Float, dyNormalized: Float) {
    val stepLen = _pdrState.value.stepLengthM
    val norm = hypot(dxNormalized, dyNormalized).coerceAtLeast(1e-4f)
    val dx = (dxNormalized / norm) * stepLen
    val dy = (dyNormalized / norm) * stepLen

    val targetAzimuth = atan2(dx, dy)
    pdrSensorEngine.feedRawAzimuthRad(targetAzimuth)

    applyUserDisplacement(dx = dx, dy = dy, measuredStepLength = stepLen)
  }

  fun rotateVirtualHeading(deltaDegrees: Float) {
    val currentRad = _pdrState.value.azimuthRad
    val nextRad = currentRad + Math.toRadians(deltaDegrees.toDouble()).toFloat()
    pdrSensorEngine.feedRawAzimuthRad(nextRad)
    val locked = _lockedMac.value?.let { deviceMap[it] } ?: return
    if (!locked.isSignalStale) {
      triggerParticleStepUpdate(locked, _pdrState.value)
    }
  }

  private fun applyUserDisplacement(dx: Float, dy: Float, measuredStepLength: Float) {
    var updatedPdr: PdrState? = null
    _pdrState.update { current ->
      val newX = (current.userX + dx).coerceIn(-25f, 25f)
      val newY = (current.userY + dy).coerceIn(-25f, 25f)
      val newTrail = (current.trajectory + (newX to newY)).takeLast(80)
      val next =
        current.copy(
          userX = newX,
          userY = newY,
          stepCount = current.stepCount + 1,
          lastWeinbergStepM = measuredStepLength,
          trajectory = newTrail,
        )
      updatedPdr = next
      next
    }

    val pdr = updatedPdr ?: return
    val locked = _lockedMac.value?.let { deviceMap[it] } ?: return
    if (!locked.isSignalStale) {
      triggerParticleStepUpdate(locked, pdr)
    }
  }

  fun updateDefaultTxPower(newTxPower: Int) {
    _defaultTxPower.value = newTxPower.coerceIn(-85, -35)
  }

  fun updateBasePathLossN(newN: Float) {
    _basePathLossN.value = newN.coerceIn(1.8f, 3.8f)
  }

  fun setGeigerEnabled(enabled: Boolean) {
    _geigerEnabled.value = enabled
  }

  fun saveDeviceCalibration(customAlias: String) {
    val locked = _lockedMac.value?.let { deviceMap[it] } ?: _devices.value.firstOrNull() ?: return
    val alias = customAlias.trim().ifBlank { locked.deviceName }
    val entity =
      CalibratedBeaconEntity(
        macAddress = locked.macAddress,
        customAlias = alias,
        vendorName = locked.vendorName,
        calibratedTxPower = _defaultTxPower.value,
        calibratedPathLossN = _basePathLossN.value,
        protocolSummary = locked.dissectedProtocol?.frameType ?: "BLE 5.x Beacon",
        updatedAtMs = System.currentTimeMillis(),
      )
    viewModelScope.launch(Dispatchers.IO) { repository.saveCalibration(entity) }
  }

  fun deleteDeviceCalibration(macAddress: String) {
    viewModelScope.launch(Dispatchers.IO) { repository.deleteCalibration(macAddress) }
  }

  fun recordCurrentHuntSession() {
    val locked = _lockedMac.value?.let { deviceMap[it] } ?: _devices.value.firstOrNull() ?: return
    val snap = _particleSnapshot.value
    val pdr = _pdrState.value
    val session =
      RadarHuntSessionEntity(
        macAddress = locked.macAddress,
        deviceName = locked.deviceName,
        vendorName = locked.vendorName,
        finalEstX = snap.estTargetX,
        finalEstY = snap.estTargetY,
        finalDistM = snap.estDistanceM,
        confidencePct = snap.confidencePct,
        stepsTaken = pdr.stepCount,
        resampleCount = snap.resampleCount,
        obstaclePresetName = "真机 BLE 2.4GHz 实测",
        timestampMs = System.currentTimeMillis(),
      )
    viewModelScope.launch(Dispatchers.IO) { repository.recordHuntSession(session) }
  }

  fun deleteHuntSession(id: Long) {
    viewModelScope.launch(Dispatchers.IO) { repository.deleteHuntSession(id) }
  }

  fun clearAllHuntSessions() {
    viewModelScope.launch(Dispatchers.IO) { repository.clearHuntSessions() }
  }

  /**
   * Starts the single hardware BLE scan coroutine WITHOUT calling refreshHardwareCapabilities(),
   * eliminating the StackOverflowError recursion cycle.
   */
  private fun startHardwareBleScanLocked(caps: HardwareCapabilities) {
    if (!caps.hasScanPermission || !caps.isBluetoothEnabled) return
    if (hardwareScanJob?.isActive == true) return

    hardwareScanJob =
      viewModelScope.launch(Dispatchers.Default) {
        bleScannerManager
          .scanBleFlow()
          .catch {
            // Gracefully handle any OEM Bluetooth stack flow errors
          }
          .collect { rawAd ->
            val stableHashAngle =
              ((rawAd.macAddress.hashCode() and 0x7FFFFFFF) % 360) * (PI.toFloat() / 180f)
            ingestAdvertisement(
              ad = rawAd,
              sonarAngleRad = stableHashAngle,
            )
            publishSortedDevices(rawAd.timestampMs)
          }
        // If flow closes (e.g., Bluetooth toggled), wait before allowing watchdog to re-check
        delay(2000L)
      }
  }

  private fun ingestAdvertisement(
    ad: RawBleAdvertisement,
    sonarAngleRad: Float,
  ) {
    val filterOut =
      signalFilterEngine.processRssi(
        macAddress = ad.macAddress,
        rawRssi = ad.rawRssi,
        timestampMs = ad.timestampMs,
      )

    val calProfile = calibrationCache[ad.macAddress]
    val fallbackTx = calProfile?.calibratedTxPower ?: _defaultTxPower.value
    val pathLossN = calProfile?.calibratedPathLossN ?: _basePathLossN.value
    val resolvedName = calProfile?.customAlias ?: ad.deviceName

    val effectiveTx =
      SignalFilterEngine.resolveEffectiveTxPower(
        rawTxPower = ad.rawTxPower,
        defaultTxPower = fallbackTx,
      )

    val estDist =
      SignalFilterEngine.estimateDistanceMeters(
        smoothedRssi = filterOut.smoothedRssi,
        effectiveTxPower = effectiveTx,
        pathLossN = pathLossN,
      )

    val dissected =
      BlePacketDissector.dissect(
        macAddress = ad.macAddress,
        manufacturerId = ad.manufacturerId,
        manufacturerBytes = ad.manufacturerBytes,
        rawTxPower = ad.rawTxPower,
        effectiveTxPower = effectiveTx,
      )

    val telemetry =
      BleDeviceTelemetry(
        macAddress = ad.macAddress,
        deviceName = resolvedName,
        vendorName = ad.vendorName,
        manufacturerId = ad.manufacturerId,
        rawRssi = ad.rawRssi,
        hampelRssi = filterOut.hampelRssi,
        smoothedRssi = filterOut.smoothedRssi,
        rawTxPower = ad.rawTxPower,
        effectiveTxPower = effectiveTx,
        estimatedDistanceM = estDist,
        packetIntervalMs = filterOut.packetIntervalMs,
        lastSeenMs = ad.timestampMs,
        isSignalStale = false,
        sonarAngleRad = sonarAngleRad,
        dissectedProtocol = dissected,
      )

    deviceMap[ad.macAddress] = telemetry

    if (_lockedMac.value == null) {
      _lockedMac.value = ad.macAddress
    }

    if (_lockedMac.value == ad.macAddress) {
      _oscilloscopeTrace.value = signalFilterEngine.getOscilloscopeTrace(ad.macAddress)
      triggerParticleStepUpdate(telemetry, _pdrState.value)
    }
  }

  private fun triggerParticleStepUpdate(telemetry: BleDeviceTelemetry, pdr: PdrState) {
    viewModelScope.launch(Dispatchers.Default) {
      val snapshot =
        particleEngine.stepUpdate(
          userX = pdr.userX,
          userY = pdr.userY,
          userAzimuthRad = pdr.azimuthRad,
          smoothedRssi = telemetry.smoothedRssi,
          effectiveTxPower = telemetry.effectiveTxPower,
          kalmanDist = telemetry.estimatedDistanceM,
          allowResample = !telemetry.isSignalStale,
          bodyShadowingEnabled = _bodyShadowingEnabled.value,
        )
      _particleSnapshot.value = snapshot
    }
  }

  private fun publishSortedDevices(nowMs: Long) {
    val list =
      deviceMap.values
        .map { dev ->
          val stale = (nowMs - dev.lastSeenMs) > SignalFilterEngine.STALE_TIMEOUT_MS
          if (stale != dev.isSignalStale) dev.copy(isSignalStale = stale) else dev
        }
        .sortedByDescending { it.smoothedRssi }
    _devices.value = list
  }

  private fun startStalenessWatchdog() {
    viewModelScope.launch(Dispatchers.Default) {
      while (isActive) {
        val nowMs = System.currentTimeMillis()
        if (deviceMap.isNotEmpty()) {
          for ((mac, dev) in deviceMap.entries) {
            val isStale = (nowMs - dev.lastSeenMs) > SignalFilterEngine.STALE_TIMEOUT_MS
            if (isStale != dev.isSignalStale) {
              deviceMap[mac] = dev.copy(isSignalStale = isStale)
            }
          }
          publishSortedDevices(nowMs)
        }
        refreshHardwareCapabilities()
        delay(500L)
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    pdrSensorEngine.stop()
    synchronized(streamLock) {
      hardwareScanJob?.cancel()
    }
  }
}
