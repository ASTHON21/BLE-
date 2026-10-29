package com.example.model

data class HardwareCapabilities(
  val isBluetoothSupported: Boolean = false,
  val isBluetoothEnabled: Boolean = false,
  val hasScanPermission: Boolean = false,
  val isLe2MPhySupported: Boolean = false,
  val isLeCodedPhySupported: Boolean = false,
  val isLeExtendedAdvertisingSupported: Boolean = false,
  val isOffloadedFilteringSupported: Boolean = false,
  val stepSensorTier: String = "L3-C (VIRTUAL_PAD)",
  val orientationSensorTier: String = "FALLBACK_COMPASS",
  val scanCooldownRemainingMs: Long = 0L,
)

data class DissectedBleProtocol(
  val frameType: String,
  val protocolStandard: String,
  val payloadHex: String,
  val fields: List<Pair<String, String>>,
)

data class OscilloscopeTrace(
  val count: Int = 0,
  val rawHistory: FloatArray = FloatArray(0),
  val hampelHistory: FloatArray = FloatArray(0),
  val kalmanHistory: FloatArray = FloatArray(0),
  val upperBandHistory: FloatArray = FloatArray(0),
  val lowerBandHistory: FloatArray = FloatArray(0),
)

data class BleDeviceTelemetry(
  val macAddress: String,
  val deviceName: String,
  val vendorName: String,
  val manufacturerId: Int?,
  val rawRssi: Int,
  val hampelRssi: Float,
  val smoothedRssi: Float,
  val rawTxPower: Int,
  val effectiveTxPower: Int,
  val estimatedDistanceM: Float,
  val packetIntervalMs: Long,
  val lastSeenMs: Long,
  val isSignalStale: Boolean,
  val sonarAngleRad: Float = 0f,
  val dissectedProtocol: DissectedBleProtocol? = null,
)

data class PdrState(
  val userX: Float = 0f,
  val userY: Float = 0f,
  val azimuthRad: Float = 0f, // 0 = +Y (North/Forward), +PI/2 = +X (East/Right)
  val smoothSin: Float = 0f,
  val smoothCos: Float = 1f,
  val stepCount: Int = 0,
  val stepLengthM: Float = 0.65f,
  val lastWeinbergStepM: Float = 0.65f,
  val trajectory: List<Pair<Float, Float>> = listOf(0f to 0f),
)

/**
 * True Zero-GC Double-Buffered Particle Snapshot:
 * - Pre-allocated primitive arrays px, py, pn, pw are reused via Ping-Pong buffer pool.
 * - Includes Primary Mode Centroid (Mode-Seeking) + Secondary Ghost Mirror Mode + Orthogonal Step Hint.
 */
class ParticleSnapshot(
  val count: Int,
  val px: FloatArray,
  val py: FloatArray,
  val pn: FloatArray,
  val pw: FloatArray,
  var estTargetX: Float = 0f,
  var estTargetY: Float = 0f,
  var ghostTargetX: Float = 0f,
  var ghostTargetY: Float = 0f,
  var bimodalRatio: Float = 0f,
  var hasBimodalAmbiguity: Boolean = false,
  var recommendedStepRad: Float = 0f,
  var orthogonalStepHint: String = "稳态收敛中",
  var estDistanceM: Float = 0f,
  var relativeBearingDeg: Float = 0f,
  var confidencePct: Float = 0f,
  var nEff: Float = 2500f,
  var meanPathLossN: Float = 2.4f,
  var resampleCount: Int = 0,
  var version: Long = 0L,
)
