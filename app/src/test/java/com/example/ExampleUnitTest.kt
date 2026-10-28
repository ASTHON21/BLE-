package com.example

import com.example.engine.BlePacketDissector
import com.example.engine.MonteCarloParticleEngine
import com.example.engine.NlosSandboxSimulator
import com.example.engine.SignalFilterEngine
import com.example.model.NlosScenarioPreset
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun txPowerDefense_rejects127AndOutOfRange() {
    assertEquals(-59, SignalFilterEngine.resolveEffectiveTxPower(127, -59))
    assertEquals(-59, SignalFilterEngine.resolveEffectiveTxPower(10, -59))
    assertEquals(-59, SignalFilterEngine.resolveEffectiveTxPower(-120, -59))
    assertEquals(-61, SignalFilterEngine.resolveEffectiveTxPower(-61, -59))
  }

  @Test
  fun hampelAndKalmanFilter_clampsMultipathSpikeAndRecordsOscilloscope() {
    val engine = SignalFilterEngine()
    val mac = "AA:BB:CC:DD:EE:01"
    var now = 1000L
    repeat(5) {
      engine.processRssi(mac, -65, now)
      now += 200L
    }
    val spikeOut = engine.processRssi(mac, -105, now)
    assertTrue(spikeOut.hampelRssi > -80f)
    assertTrue(spikeOut.smoothedRssi.isFinite())

    val trace = engine.getOscilloscopeTrace(mac)
    assertEquals(6, trace.count)
    assertEquals(-105f, trace.rawHistory.last(), 0.01f)
  }

  @Test
  fun particleEngine_pingPongZeroGcAndBimodalModeSeeking() {
    val engine = MonteCarloParticleEngine()
    engine.resetParticles(centerX = 0f, centerY = 0f, initialRadiusHint = 4.0f, basePathLossN = 2.4f)
    val snap1 =
      engine.stepUpdate(
        userX = 0f,
        userY = 0f,
        userAzimuthRad = 0f,
        smoothedRssi = -68f,
        effectiveTxPower = -59,
        kalmanDist = 3.2f,
      )
    val snap2 =
      engine.stepUpdate(
        userX = 0.65f,
        userY = 0f,
        userAzimuthRad = 1.57f,
        smoothedRssi = -66f,
        effectiveTxPower = -59,
        kalmanDist = 2.8f,
      )
    val snap3 =
      engine.stepUpdate(
        userX = 1.30f,
        userY = 0f,
        userAzimuthRad = 1.57f,
        smoothedRssi = -64f,
        effectiveTxPower = -59,
        kalmanDist = 2.4f,
      )
    // Verify A/B Ping-Pong zero-GC pool reuses the exact two snapshot instances
    assertNotSame(snap1, snap2)
    assertSame(snap1, snap3)
    assertEquals(2500, snap3.count)
    assertTrue(snap3.estTargetX.isFinite())
    assertTrue(snap3.estTargetY.isFinite())
    assertTrue(snap3.ghostTargetX.isFinite())
    assertTrue(abs(snap3.pw.sum() - 1.0f) < 1e-3f)
  }

  @Test
  fun nlosSimulator_computesWallIntersectionLoss() {
    val walls = NlosSandboxSimulator.getObstaclesForPreset(NlosScenarioPreset.SINGLE_WALL)
    // Wall is at y = -0.8f from x = -4.8f to +4.8f
    val occludedLoss = NlosSandboxSimulator.computeWallAttenuationDb(0f, 0f, 3.5f, -2.8f, walls)
    val clearLoss = NlosSandboxSimulator.computeWallAttenuationDb(0f, 0f, 0f, 3.0f, walls)
    assertEquals(6.5f, occludedLoss, 0.01f)
    assertEquals(0f, clearLoss, 0.01f)
  }

  @Test
  fun blePacketDissector_decodesAppleAndXiaomi127Fallback() {
    val apple = BlePacketDissector.dissect("F4:0F:24:8A:91:01", 0x004C, null, -59, -59)
    assertTrue(apple.protocolStandard.contains("Apple"))
    val xiaomi = BlePacketDissector.dissect("68:AB:BC:3E:19:02", 0x038F, null, 127, -59)
    assertTrue(xiaomi.fields.any { it.second.contains("127") })
  }
}
