package com.example.engine

import android.util.SparseArray
import com.example.model.OscilloscopeTrace
import kotlin.math.abs
import kotlin.math.pow

/**
 * L2 Temporal Filter Engine & Real-Time Oscilloscope Buffer:
 * - Manufacturer Specific Data dictionary lookup
 * - TxPower boundary validation (never allows 127 or out-of-range values into distance math)
 * - 7-sample sliding window Hampel outlier filter (3 * MAD clamp)
 * - 1D Kalman Filter (Q = 0.08f, R = 3.5f)
 * - 36-sample circular oscilloscope history for 3-trace waveform visualization
 */
class SignalFilterEngine {

  companion object {
    const val TX_POWER_NOT_PRESENT = 127
    const val DEFAULT_TX_POWER_DBM = -59
    const val KALMAN_Q = 0.08f
    const val KALMAN_R = 3.5f
    private const val WINDOW_SIZE = 7
    private const val OSC_HISTORY_SIZE = 36
    private const val HAMPEL_SIGMA_SCALE = 1.4826f
    private const val HAMPEL_THRESHOLD = 3.0f
    const val STALE_TIMEOUT_MS = 3000L

    private val VENDOR_DICTIONARY =
      mapOf(
        0x004C to "Apple",
        0x00E0 to "Google",
        0x0075 to "Samsung",
        0x038F to "Xiaomi",
        0x012D to "Sony",
        0x0006 to "Microsoft",
        0x0059 to "Nordic",
        0x000D to "TI",
        0x02D5 to "Huawei",
        0x0157 to "Anhui Huami",
      )

    fun resolveVendor(manufacturerData: SparseArray<ByteArray>?): Pair<Int?, String> {
      if (manufacturerData == null || manufacturerData.size() == 0) {
        return null to "Generic BLE"
      }
      val companyId = manufacturerData.keyAt(0)
      val name =
        VENDOR_DICTIONARY[companyId]
          ?: "Vendor 0x${companyId.toString(16).uppercase().padStart(4, '0')}"
      return companyId to name
    }

    fun resolveVendorById(companyId: Int?): String {
      if (companyId == null) return "Generic BLE"
      return VENDOR_DICTIONARY[companyId]
        ?: "Vendor 0x${companyId.toString(16).uppercase().padStart(4, '0')}"
    }

    /**
     * Contract 1.3: Validate ScanResult.txPower against 127 (TX_POWER_NOT_PRESENT) and valid range
     * [-100, -20]. Falls back to user-configured defaultTxPower (default -59 dBm).
     */
    fun resolveEffectiveTxPower(
      rawTxPower: Int,
      defaultTxPower: Int = DEFAULT_TX_POWER_DBM,
    ): Int {
      val safeDefault = defaultTxPower.coerceIn(-100, -20)
      return if (rawTxPower != TX_POWER_NOT_PRESENT && rawTxPower in -100..-20) {
        rawTxPower
      } else {
        safeDefault
      }
    }

    /** Log-distance path-loss distance estimate with NaN/infinity protection. */
    fun estimateDistanceMeters(
      smoothedRssi: Float,
      effectiveTxPower: Int,
      pathLossN: Float,
    ): Float {
      val safeN = pathLossN.coerceIn(1.8f, 4.5f)
      val safeRssi = smoothedRssi.coerceIn(-115f, -15f)
      val exponent = (effectiveTxPower.toFloat() - safeRssi) / (10f * safeN)
      val dist = 10.0.pow(exponent.toDouble()).toFloat()
      return if (dist.isFinite()) dist.coerceIn(0.1f, 50.0f) else 10.0f
    }
  }

  private class DeviceFilterState {
    val window = FloatArray(WINDOW_SIZE)
    val sortScratch = FloatArray(WINDOW_SIZE)
    val devScratch = FloatArray(WINDOW_SIZE)
    var count = 0
    var writeIndex = 0
    var kalmanEstimate = Float.NaN
    var kalmanErrorCov = 1.0f
    var lastTimestampMs = 0L

    // Circular oscilloscope waveform buffers
    val oscRaw = FloatArray(OSC_HISTORY_SIZE)
    val oscHampel = FloatArray(OSC_HISTORY_SIZE)
    val oscKalman = FloatArray(OSC_HISTORY_SIZE)
    val oscUpperBand = FloatArray(OSC_HISTORY_SIZE)
    val oscLowerBand = FloatArray(OSC_HISTORY_SIZE)
    var oscCount = 0
    var oscWriteIdx = 0
  }

  private val deviceStates = HashMap<String, DeviceFilterState>()

  data class FilterOutput(
    val hampelRssi: Float,
    val smoothedRssi: Float,
    val packetIntervalMs: Long,
  )

  @Synchronized
  fun processRssi(macAddress: String, rawRssi: Int, timestampMs: Long): FilterOutput {
    val clampedRaw = rawRssi.coerceIn(-110, -15).toFloat()
    val state = deviceStates.getOrPut(macAddress) { DeviceFilterState() }

    // Record into 7-sample circular window
    state.window[state.writeIndex] = clampedRaw
    state.writeIndex = (state.writeIndex + 1) % WINDOW_SIZE
    if (state.count < WINDOW_SIZE) {
      state.count++
    }

    // 1. Hampel Filter (3 * MAD outlier clamping)
    val n = state.count
    for (i in 0 until n) {
      state.sortScratch[i] = state.window[i]
    }
    state.sortScratch.sort(0, n)
    val median =
      if (n % 2 == 1) {
        state.sortScratch[n / 2]
      } else {
        0.5f * (state.sortScratch[n / 2 - 1] + state.sortScratch[n / 2])
      }

    for (i in 0 until n) {
      state.devScratch[i] = abs(state.window[i] - median)
    }
    state.devScratch.sort(0, n)
    val mad =
      if (n % 2 == 1) {
        state.devScratch[n / 2]
      } else {
        0.5f * (state.devScratch[n / 2 - 1] + state.devScratch[n / 2])
      }

    val sigmaEst = (HAMPEL_SIGMA_SCALE * mad).coerceAtLeast(0.8f)
    val maxAllowed = HAMPEL_THRESHOLD * sigmaEst
    val diff = clampedRaw - median
    val hampelFiltered =
      if (n >= 3 && abs(diff) > maxAllowed) {
        if (diff > 0f) median + maxAllowed else median - maxAllowed
      } else {
        clampedRaw
      }

    // 2. 1D Kalman Filter (Q = 0.08f, R = 3.5f)
    if (state.kalmanEstimate.isNaN() || !state.kalmanEstimate.isFinite()) {
      state.kalmanEstimate = hampelFiltered
      state.kalmanErrorCov = 1.0f
    } else {
      val predCov = state.kalmanErrorCov + KALMAN_Q
      val kalmanGain = predCov / (predCov + KALMAN_R)
      state.kalmanEstimate =
        state.kalmanEstimate + kalmanGain * (hampelFiltered - state.kalmanEstimate)
      state.kalmanErrorCov = (1f - kalmanGain) * predCov
    }

    // 3. Record into 36-sample Oscilloscope circular buffer
    val idx = state.oscWriteIdx
    state.oscRaw[idx] = clampedRaw
    state.oscHampel[idx] = hampelFiltered
    state.oscKalman[idx] = state.kalmanEstimate
    state.oscUpperBand[idx] = (median + maxAllowed).coerceIn(-110f, -15f)
    state.oscLowerBand[idx] = (median - maxAllowed).coerceIn(-110f, -15f)
    state.oscWriteIdx = (idx + 1) % OSC_HISTORY_SIZE
    if (state.oscCount < OSC_HISTORY_SIZE) {
      state.oscCount++
    }

    val interval =
      if (state.lastTimestampMs > 0L && timestampMs >= state.lastTimestampMs) {
        (timestampMs - state.lastTimestampMs).coerceAtMost(9999L)
      } else {
        200L
      }
    state.lastTimestampMs = timestampMs

    return FilterOutput(
      hampelRssi = hampelFiltered,
      smoothedRssi = state.kalmanEstimate,
      packetIntervalMs = interval,
    )
  }

  @Synchronized
  fun getOscilloscopeTrace(macAddress: String?): OscilloscopeTrace {
    if (macAddress == null) return OscilloscopeTrace()
    val state = deviceStates[macAddress] ?: return OscilloscopeTrace()
    val n = state.oscCount
    if (n == 0) return OscilloscopeTrace()

    val rawOut = FloatArray(n)
    val hampelOut = FloatArray(n)
    val kalmanOut = FloatArray(n)
    val upperOut = FloatArray(n)
    val lowerOut = FloatArray(n)

    val startIdx = if (n < OSC_HISTORY_SIZE) 0 else state.oscWriteIdx
    for (i in 0 until n) {
      val ringIdx = (startIdx + i) % OSC_HISTORY_SIZE
      rawOut[i] = state.oscRaw[ringIdx]
      hampelOut[i] = state.oscHampel[ringIdx]
      kalmanOut[i] = state.oscKalman[ringIdx]
      upperOut[i] = state.oscUpperBand[ringIdx]
      lowerOut[i] = state.oscLowerBand[ringIdx]
    }
    return OscilloscopeTrace(
      count = n,
      rawHistory = rawOut,
      hampelHistory = hampelOut,
      kalmanHistory = kalmanOut,
      upperBandHistory = upperOut,
      lowerBandHistory = lowerOut,
    )
  }

  @Synchronized
  fun clear() {
    deviceStates.clear()
  }
}
