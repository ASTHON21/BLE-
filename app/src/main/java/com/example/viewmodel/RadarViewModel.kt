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
import com.example.engine.NlosSandboxSimulator
import com.example.engine.SignalFilterEngine
import com.example.hardware.BleScannerManager
import com.example.hardware.PdrSensorEngine
import com.example.hardware.RawBleAdvertisement
import com.example.model.BleDeviceTelemetry
import com.example.model.HardwareCapabilities
import com.example.model.NlosScenarioPreset
import com.example.model.OscilloscopeTrace
import com.example.model.ParticleSnapshot
import com.example.model.PdrState
import com.example.model.WallObstacle
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

  private val _isSandboxMode = MutableStateFlow(true)
  val isSandboxMode: StateFlow<Boolean> = _isSandboxMode.asStateFlow()

  private val _nlosPreset = MutableStateFlow(NlosScenarioPreset.SINGLE_WALL)
  val nlosPreset: StateFlow<NlosScenarioPreset> = _nlosPreset.asStateFlow()

  private val _obstacles =
    MutableStateFlow(
      NlosSandboxSimulator.getObstaclesForPreset(NlosScenarioPreset.SINGLE_WALL)
    )
  val obstacles: StateFlow<List<WallObstacle>> = _obstacles.asStateFlow()

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
  private var hardwareScanJob: Job? = null
  private var sandboxSimJob: Job? = null
  private val simRng = Random(2026L)

  private data class VirtualBeaconSpec(
    val macAddress: String,
    val deviceName: String,
    val companyId: Int,
    val rawTxPower: Int,
    val truePathLossN: Float,
    var trueX: Float,
    var trueY: Float,
  )

  private val sandboxBeacons =
    mutableListOf(
      VirtualBeaconSpec(
        macAddress = "F4:0F:24:8A:91:01",
        deviceName = "AirTag Pro [目标信标]",
        companyId = 0x004C, // Apple
        rawTxPower = -59,
        truePathLossN = 2.35f,
        trueX = 3.5f,
        trueY = -2.8f,
      ),
      VirtualBeaconSpec(
        macAddress = "68:AB:BC:3E:19:02",
        deviceName = "Xiaomi Tag Ultra",
        companyId = 0x038F, // Xiaomi
        rawTxPower = 127, // Exercises Contract 1.3 TX_POWER_NOT_PRESENT (127) fallback!
        truePathLossN = 2.50f,
        trueX = -2.6f,
        trueY = 3.1f,
      ),
      VirtualBeaconSpec(
        macAddress = "D0:03:DF:77:4C:03",
        deviceName = "Galaxy SmartTag2",
        companyId = 0x0075, // Samsung
        rawTxPower = -57,
        truePathLossN = 2.40f,
        trueX = 4.1f,
        trueY = 2.4f,
      ),
      VirtualBeaconSpec(
        macAddress = "3C:28:6D:52:B0:04",
        deviceName = "Pixel Finder Tag",
        companyId = 0x00E0, // Google
        rawTxPower = -61,
        truePathLossN = 2.60f,
        trueX = -3.4f,
        trueY = -2.2f,
      ),
      VirtualBeaconSpec(
        macAddress = "94:DB:56:11:E8:05",
        deviceName = "Sony WF-1000XM5 Case",
        companyId = 0x012D, // Sony
        rawTxPower = -60,
        truePathLossN = 2.45f,
        trueX = 1.6f,
        trueY = 4.6f,
      ),
    )

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
    restartActiveDataStream()
  }

  fun requiredPermissions(): Array<String> = bleScannerManager.requiredPermissions()

  fun refreshHardwareCapabilities() {
    val caps =
      bleScannerManager.queryCapabilities(
        stepTier = pdrSensorEngine.stepSensorTierName,
        orientationTier = pdrSensorEngine.orientationSensorTierName,
      )
    _hardwareCapabilities.value = caps
    if (!_isSandboxMode.value && caps.hasScanPermission && caps.isBluetoothEnabled) {
      if (hardwareScanJob == null || hardwareScanJob?.isActive != true) {
        restartActiveDataStream()
      }
    }
  }

  fun setSandboxMode(enabled: Boolean) {
    if (_isSandboxMode.value == enabled) return
    _isSandboxMode.value = enabled
    deviceMap.clear()
    signalFilterEngine.clear()
    _devices.value = emptyList()
    _oscilloscopeTrace.value = OscilloscopeTrace()
    refreshHardwareCapabilities()
    restartActiveDataStream()
  }

  fun selectNlosPreset(preset: NlosScenarioPreset) {
    _nlosPreset.value = preset
    _obstacles.value = NlosSandboxSimulator.getObstaclesForPreset(preset)
  }

  fun setBodyShadowingEnabled(enabled: Boolean) {
    _bodyShadowingEnabled.value = enabled
  }

  fun selectTab(tab: RadarTab) {
    _activeTab.value = tab
  }

  fun lockOnDevice(macAddress: String) {
    _lockedMac.value = macAddress
    // Apply calibrated profile if present
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

  fun randomizeSandboxTargetPositions() {
    if (!_isSandboxMode.value) return
    sandboxBeacons.forEachIndexed { idx, beacon ->
      val angle = (idx * (2.0 * PI / sandboxBeacons.size)) + simRng.nextDouble() * 0.9
      val radius = 2.4f + simRng.nextFloat() * 3.6f
      beacon.trueX = (radius * cos(angle)).toFloat()
      beacon.trueY = (radius * sin(angle)).toFloat()
    }
    resetParticleCloud()
  }

  /**
   * Contract 2.2 Priority C: Manual & Sandbox Virtual D-Pad Stepping.
   */
  fun stepVirtualDirection(dxNormalized: Float, dyNormalized: Float) {
    val stepLen = _pdrState.value.stepLengthM
    val norm = hypot(dxNormalized, dyNormalized).coerceAtLeast(1e-4f)
    val dx = (dxNormalized / norm) * stepLen
    val dy = (dyNormalized / norm) * stepLen

    val targetAzimuth = atan2(dx, dy)
    pdrSensorEngine.feedRawAzimuthRad(targetAzimuth)

    applyUserDisplacement(dx = dx, dy = dy, measuredStepLength = stepLen)
  }

  /**
   * L4+ In-Place 360-degree Body Rotation: Allows user to rotate heading in place to break
   * symmetry via the 2.4GHz Human Body Torso Shadowing model even without taking a step.
   */
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
    val presetName =
      if (_isSandboxMode.value) _nlosPreset.value.title else "真机 BLE 2.4GHz 实测"
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
        obstaclePresetName = presetName,
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

  private fun restartActiveDataStream() {
    hardwareScanJob?.cancel()
    sandboxSimJob?.cancel()

    if (_isSandboxMode.value) {
      startSandboxSimulation()
    } else {
      startHardwareBleScan()
    }
  }

  private fun startSandboxSimulation() {
    if (_lockedMac.value == null && sandboxBeacons.isNotEmpty()) {
      _lockedMac.value = sandboxBeacons.first().macAddress
    }
    sandboxSimJob =
      viewModelScope.launch(Dispatchers.Default) {
        while (isActive && _isSandboxMode.value) {
          val nowMs = System.currentTimeMillis()
          val pdr = _pdrState.value
          val activeWalls = _obstacles.value
          val useBodyShadow = _bodyShadowingEnabled.value
          val headingSin = sin(pdr.azimuthRad)
          val headingCos = cos(pdr.azimuthRad)

          for (beacon in sandboxBeacons) {
            val dx = beacon.trueX - pdr.userX
            val dy = beacon.trueY - pdr.userY
            val trueDist = hypot(dx, dy).coerceAtLeast(0.15f)
            val trueEffectiveTx =
              SignalFilterEngine.resolveEffectiveTxPower(beacon.rawTxPower, _defaultTxPower.value)

            // 1. NLOS Wall Intersection Attenuation
            val wallLossDb =
              NlosSandboxSimulator.computeWallAttenuationDb(
                ux = pdr.userX,
                uy = pdr.userY,
                bx = beacon.trueX,
                by = beacon.trueY,
                obstacles = activeWalls,
              )

            // 2. Human Body Torso Shadowing Directional Loss
            val bodyShadowDb =
              if (useBodyShadow) {
                val cosDelta = (dx * headingSin + dy * headingCos) / trueDist
                MonteCarloParticleEngine.BODY_SHADOW_MAX_DB *
                  0.5f *
                  (1f - cosDelta.coerceIn(-1f, 1f))
              } else {
                0f
              }

            val idealRssi =
              trueEffectiveTx.toFloat() -
                10f * beacon.truePathLossN * log10(trueDist) -
                wallLossDb -
                bodyShadowDb

            // Multipath Gaussian noise + 8% chance of deep multipath spike (to test Hampel filter)
            val gaussianNoise = simRng.nextGaussian().toFloat() * 2.0f
            val multipathSpike =
              if (simRng.nextFloat() < 0.08f) {
                if (simRng.nextBoolean()) -13.5f else 11.0f
              } else {
                0f
              }

            val simulatedRawRssi =
              (idealRssi + gaussianNoise + multipathSpike).roundToInt().coerceIn(-105, -22)

            val angleToBeacon = atan2(dx, dy)

            ingestAdvertisement(
              ad =
                RawBleAdvertisement(
                  macAddress = beacon.macAddress,
                  deviceName = beacon.deviceName,
                  manufacturerId = beacon.companyId,
                  vendorName = SignalFilterEngine.resolveVendorById(beacon.companyId),
                  manufacturerBytes = null,
                  rawRssi = simulatedRawRssi,
                  rawTxPower = beacon.rawTxPower,
                  timestampMs = nowMs,
                ),
              isSimulated = true,
              simTrueX = beacon.trueX,
              simTrueY = beacon.trueY,
              sonarAngleRad = angleToBeacon,
              isNlosOccluded = wallLossDb > 0f,
              wallLossDb = wallLossDb,
            )
          }

          publishSortedDevices(nowMs)
          delay(200L) // 5Hz telemetry stream
        }
      }
  }

  private fun startHardwareBleScan() {
    refreshHardwareCapabilities()
    val caps = _hardwareCapabilities.value
    if (!caps.hasScanPermission || !caps.isBluetoothEnabled) return

    hardwareScanJob =
      viewModelScope.launch(Dispatchers.Default) {
        bleScannerManager.scanBleFlow().collect { rawAd ->
          val stableHashAngle =
            ((rawAd.macAddress.hashCode() and 0x7FFFFFFF) % 360) * (PI.toFloat() / 180f)
          ingestAdvertisement(
            ad = rawAd,
            isSimulated = false,
            simTrueX = 0f,
            simTrueY = 0f,
            sonarAngleRad = stableHashAngle,
            isNlosOccluded = false,
            wallLossDb = 0f,
          )
          publishSortedDevices(rawAd.timestampMs)
        }
      }
  }

  private fun ingestAdvertisement(
    ad: RawBleAdvertisement,
    isSimulated: Boolean,
    simTrueX: Float,
    simTrueY: Float,
    sonarAngleRad: Float,
    isNlosOccluded: Boolean,
    wallLossDb: Float,
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

    // Contract 1.3: Never allow 127 (TX_POWER_NOT_PRESENT) or out-of-bounds TxPower into distance math
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
        isSimulated = isSimulated,
        simTrueX = simTrueX,
        simTrueY = simTrueY,
        sonarAngleRad = sonarAngleRad,
        isNlosOccluded = isNlosOccluded,
        wallLossDb = wallLossDb,
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
        delay(300L)
      }
    }
  }

  override fun onCleared() {
    super.onCleared()
    pdrSensorEngine.stop()
    hardwareScanJob?.cancel()
    sandboxSimJob?.cancel()
  }
}
