package com.example.engine

import com.example.model.ParticleSnapshot
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Contract 3 & L4+ Upgrade: True Zero-GC SoA Monte Carlo Particle Filter Engine
 * - Capacity N = 2500 flat primitive FloatArrays (px, py, pn, pw)
 * - A/B Ping-Pong Double-Buffered Snapshot Pool (zero .copyOf() heap allocations per frame)
 * - 16x16 Spatial Density Grid Mode-Seeking: Resolves Primary Target Mode + Secondary Mirror Ghost Mode
 *   to eliminate the bimodal midpoint averaging trap and compute Orthogonal Step Advice
 * - 2.4GHz Human Body Shadowing Directional Gain Model: Enables 360-degree in-place turn symmetry breaking
 */
class MonteCarloParticleEngine {

  companion object {
    const val N = 2500
    private const val SURVIVAL_COUNT = 2375 // 95% of 2500
    private const val DEFAULT_SIGMA = 4.5f
    private const val MIN_DIST_METERS = 0.15f
    private const val MIN_WEIGHT_FLOOR = 1e-12f
    private const val MIN_WEIGHT_SUM = 1e-9f
    private const val POS_JITTER_STD = 0.12f
    private const val N_JITTER_STD = 0.03f

    // 16x16 Spatial Density Grid for Bimodal Symmetry Breaking & Mode-Seeking
    private const val GRID_DIM = 16
    private const val GRID_CELLS = GRID_DIM * GRID_DIM
    private const val GRID_HALF_SPAN_M = 12.0f
    private const val GRID_CELL_SIZE_M = (2f * GRID_HALF_SPAN_M) / GRID_DIM

    // 2.4GHz Human Body Torso Shadowing Max Attenuation (dB) when beacon is behind user
    const val BODY_SHADOW_MAX_DB = 4.6f
  }

  // Primary Struct-of-Arrays (Zero object allocation per particle)
  private val px = FloatArray(N)
  private val py = FloatArray(N)
  private val pn = FloatArray(N)
  private val pw = FloatArray(N)

  // Pre-allocated scratch buffers for zero-GC resampling
  private val scratchX = FloatArray(N)
  private val scratchY = FloatArray(N)
  private val scratchN = FloatArray(N)
  private val cdf = FloatArray(N)

  // Pre-allocated 16x16 spatial density grid buffers (zero-GC mode-seeking)
  private val gridWeights = FloatArray(GRID_CELLS)
  private val gridSumX = FloatArray(GRID_CELLS)
  private val gridSumY = FloatArray(GRID_CELLS)

  // A/B Double-Buffered Ping-Pong Snapshot Pool (eliminates .copyOf() allocations)
  private val snapshotPool =
    arrayOf(
      ParticleSnapshot(
        count = N,
        px = FloatArray(N),
        py = FloatArray(N),
        pn = FloatArray(N),
        pw = FloatArray(N),
      ),
      ParticleSnapshot(
        count = N,
        px = FloatArray(N),
        py = FloatArray(N),
        pn = FloatArray(N),
        pw = FloatArray(N),
      ),
    )
  private var activePoolIndex = 0

  private val rng = Random(42L)
  private var resampleCount = 0
  private var snapshotVersion = 0L

  init {
    resetParticles(centerX = 0f, centerY = 0f, initialRadiusHint = 4.0f, basePathLossN = 2.4f)
  }

  @Synchronized
  fun resetParticles(
    centerX: Float,
    centerY: Float,
    initialRadiusHint: Float = 4.5f,
    basePathLossN: Float = 2.4f,
  ) {
    val uniformWeight = 1f / N
    val maxR = (initialRadiusHint * 2.1f).coerceIn(4.0f, 14.0f)
    for (i in 0 until N) {
      val angle = rng.nextFloat() * (2f * PI.toFloat())
      val r =
        if (i % 3 == 0) {
          (initialRadiusHint + (rng.nextGaussian().toFloat() * 1.3f)).coerceIn(0.3f, maxR)
        } else {
          sqrt(rng.nextFloat()) * maxR
        }
      px[i] = centerX + r * cos(angle)
      py[i] = centerY + r * sin(angle)
      val rawN =
        if (i % 2 == 0) {
          2.0f + rng.nextFloat() * 1.6f
        } else {
          (basePathLossN + rng.nextGaussian().toFloat() * 0.28f).coerceIn(2.0f, 3.6f)
        }
      pn[i] = rawN
      pw[i] = uniformWeight
    }
    resampleCount = 0
    snapshotVersion++
  }

  /**
   * Contract 3.3 & 3.4 + L4+ Body Shadowing & Grid Mode-Seeking Update.
   */
  @Synchronized
  fun stepUpdate(
    userX: Float,
    userY: Float,
    userAzimuthRad: Float,
    smoothedRssi: Float,
    effectiveTxPower: Int,
    kalmanDist: Float,
    sigma: Float = DEFAULT_SIGMA,
    allowResample: Boolean = true,
    bodyShadowingEnabled: Boolean = true,
  ): ParticleSnapshot {
    val safeSigma = sigma.coerceAtLeast(1.0f)
    val twoSigmaSq = 2f * safeSigma * safeSigma
    val txPowerF = effectiveTxPower.toFloat()
    val headingSin = sin(userAzimuthRad)
    val headingCos = cos(userAzimuthRad)

    // 1. Likelihood Scoring with Singularity Defense & Optional 2.4GHz Body Shadowing
    var weightSum = 0f
    for (i in 0 until N) {
      val dx = px[i] - userX
      val dy = py[i] - userY
      val dist = hypot(dx, dy).coerceAtLeast(MIN_DIST_METERS)

      // Optional 2.4GHz Human Body Torso Shadowing (attenuates signals behind user's facing vector)
      var bodyShadowLossDb = 0f
      if (bodyShadowingEnabled) {
        // Cosine of angle between user facing vector (sin(az), cos(az)) and particle vector (dx, dy)
        val cosDelta = (dx * headingSin + dy * headingCos) / dist
        // cosDelta = +1 (directly ahead -> 0dB loss), cosDelta = -1 (directly behind torso -> ~4.6dB loss)
        bodyShadowLossDb = BODY_SHADOW_MAX_DB * 0.5f * (1f - cosDelta.coerceIn(-1f, 1f))
      }

      val predRssi = txPowerF - 10f * pn[i] * log10(dist) - bodyShadowLossDb
      val diff = smoothedRssi - predRssi
      val updatedW = (pw[i] * exp(-(diff * diff) / twoSigmaSq)).coerceAtLeast(MIN_WEIGHT_FLOOR)
      pw[i] = updatedW
      weightSum += updatedW
    }

    // 2. Normalization with Divide-by-Zero / NaN Defense
    val uniformWeight = 1f / N
    var sumSqWeights = 0f
    if (!weightSum.isFinite() || weightSum <= MIN_WEIGHT_SUM) {
      for (i in 0 until N) {
        pw[i] = uniformWeight
      }
      sumSqWeights = 1f / N
    } else {
      for (i in 0 until N) {
        val nw = pw[i] / weightSum
        pw[i] = nw
        sumSqWeights += nw * nw
      }
    }

    // 3. Effective Particle Count N_eff = 1 / sum(pw[i]^2)
    val nEff =
      if (sumSqWeights.isFinite() && sumSqWeights > 1e-12f) {
        (1f / sumSqWeights).coerceIn(1f, N.toFloat())
      } else {
        N.toFloat()
      }

    // 4. Contract 3.4: 95% Systematic Resampling + 5% Ring Random Injection
    if (allowResample && nEff < 0.4f * N) {
      performHybridResampling(userX = userX, userY = userY, kalmanDist = kalmanDist)
      resampleCount++
    }

    // 5. Build Zero-GC Ping-Pong Snapshot with Spatial Grid Mode-Seeking
    return buildPingPongSnapshot(
      userX = userX,
      userY = userY,
      userAzimuthRad = userAzimuthRad,
      nEff = nEff,
    )
  }

  private fun performHybridResampling(userX: Float, userY: Float, kalmanDist: Float) {
    var running = 0f
    for (i in 0 until N) {
      running += pw[i]
      cdf[i] = running
    }
    cdf[N - 1] = 1.0f

    val step = 1f / SURVIVAL_COUNT
    val u0 = rng.nextFloat() * step
    var cdfIdx = 0
    for (m in 0 until SURVIVAL_COUNT) {
      val u = u0 + m * step
      while (cdfIdx < N - 1 && u > cdf[cdfIdx]) {
        cdfIdx++
      }
      val jitterX = (rng.nextGaussian().toFloat() * POS_JITTER_STD).coerceIn(-0.24f, 0.24f)
      val jitterY = (rng.nextGaussian().toFloat() * POS_JITTER_STD).coerceIn(-0.24f, 0.24f)
      val jitterN = (rng.nextGaussian().toFloat() * N_JITTER_STD).coerceIn(-0.06f, 0.06f)

      scratchX[m] = px[cdfIdx] + jitterX
      scratchY[m] = py[cdfIdx] + jitterY
      scratchN[m] = (pn[cdfIdx] + jitterN).coerceIn(1.8f, 4.0f)
    }

    val ringRadius = kalmanDist.coerceIn(0.35f, 18.0f)
    for (m in SURVIVAL_COUNT until N) {
      val theta = rng.nextFloat() * (2f * PI.toFloat())
      val r =
        (ringRadius + rng.nextGaussian().toFloat() * 0.22f).coerceAtLeast(MIN_DIST_METERS)
      scratchX[m] = userX + r * cos(theta)
      scratchY[m] = userY + r * sin(theta)
      scratchN[m] = (2.0f + rng.nextFloat() * 1.6f).coerceIn(1.8f, 4.0f)
    }

    val uniformW = 1f / N
    for (i in 0 until N) {
      px[i] = scratchX[i]
      py[i] = scratchY[i]
      pn[i] = scratchN[i]
      pw[i] = uniformW
    }
  }

  @Synchronized
  fun currentSnapshot(userX: Float, userY: Float, userAzimuthRad: Float): ParticleSnapshot {
    var sumSq = 0f
    for (i in 0 until N) {
      sumSq += pw[i] * pw[i]
    }
    val nEff = if (sumSq > 1e-12f) (1f / sumSq).coerceIn(1f, N.toFloat()) else N.toFloat()
    return buildPingPongSnapshot(
      userX = userX,
      userY = userY,
      userAzimuthRad = userAzimuthRad,
      nEff = nEff,
    )
  }

  private fun buildPingPongSnapshot(
    userX: Float,
    userY: Float,
    userAzimuthRad: Float,
    nEff: Float,
  ): ParticleSnapshot {
    activePoolIndex = 1 - activePoolIndex
    val snap = snapshotPool[activePoolIndex]

    // Clear 16x16 spatial density grid
    for (c in 0 until GRID_CELLS) {
      gridWeights[c] = 0f
      gridSumX[c] = 0f
      gridSumY[c] = 0f
    }

    var globalMeanX = 0f
    var globalMeanY = 0f
    var meanN = 0f

    val minX = userX - GRID_HALF_SPAN_M
    val minY = userY - GRID_HALF_SPAN_M

    for (i in 0 until N) {
      val x = px[i]
      val y = py[i]
      val nVal = pn[i]
      val w = pw[i]

      globalMeanX += x * w
      globalMeanY += y * w
      meanN += nVal * w

      // Copy into pre-allocated Ping-Pong buffer (Zero heap allocation)
      snap.px[i] = x
      snap.py[i] = y
      snap.pn[i] = nVal
      snap.pw[i] = w

      // Bin into 16x16 spatial density grid
      val gx = ((x - minX) / GRID_CELL_SIZE_M).toInt().coerceIn(0, GRID_DIM - 1)
      val gy = ((y - minY) / GRID_CELL_SIZE_M).toInt().coerceIn(0, GRID_DIM - 1)
      val cellIdx = gy * GRID_DIM + gx
      gridWeights[cellIdx] += w
      gridSumX[cellIdx] += x * w
      gridSumY[cellIdx] += y * w
    }

    if (!globalMeanX.isFinite()) globalMeanX = userX
    if (!globalMeanY.isFinite()) globalMeanY = userY
    if (!meanN.isFinite()) meanN = 2.4f

    // Find Primary Mode Peak (3x3 neighborhood around highest density cell)
    var bestCell = 0
    var bestCellW = -1f
    for (c in 0 until GRID_CELLS) {
      if (gridWeights[c] > bestCellW) {
        bestCellW = gridWeights[c]
        bestCell = c
      }
    }
    val bestGx = bestCell % GRID_DIM
    val bestGy = bestCell / GRID_DIM

    var primaryW = 0f
    var primarySumX = 0f
    var primarySumY = 0f
    for (dy in -1..1) {
      val ny = bestGy + dy
      if (ny !in 0 until GRID_DIM) continue
      for (dx in -1..1) {
        val nx = bestGx + dx
        if (nx !in 0 until GRID_DIM) continue
        val idx = ny * GRID_DIM + nx
        primaryW += gridWeights[idx]
        primarySumX += gridSumX[idx]
        primarySumY += gridSumY[idx]
      }
    }

    val targetX = if (primaryW > 1e-5f) primarySumX / primaryW else globalMeanX
    val targetY = if (primaryW > 1e-5f) primarySumY / primaryW else globalMeanY

    // Find Secondary Mirror Ghost Peak (at least 2 grid cells away from Primary Peak)
    var secondCell = -1
    var secondCellW = -1f
    for (c in 0 until GRID_CELLS) {
      val gx = c % GRID_DIM
      val gy = c / GRID_DIM
      if (abs(gx - bestGx) <= 2 && abs(gy - bestGy) <= 2) continue
      if (gridWeights[c] > secondCellW) {
        secondCellW = gridWeights[c]
        secondCell = c
      }
    }

    var ghostX = targetX
    var ghostY = targetY
    var secondaryW = 0f
    if (secondCell >= 0) {
      val secGx = secondCell % GRID_DIM
      val secGy = secondCell / GRID_DIM
      var secSumX = 0f
      var secSumY = 0f
      for (dy in -1..1) {
        val ny = secGy + dy
        if (ny !in 0 until GRID_DIM) continue
        for (dx in -1..1) {
          val nx = secGx + dx
          if (nx !in 0 until GRID_DIM) continue
          val idx = ny * GRID_DIM + nx
          secondaryW += gridWeights[idx]
          secSumX += gridSumX[idx]
          secSumY += gridSumY[idx]
        }
      }
      if (secondaryW > 1e-5f) {
        ghostX = secSumX / secondaryW
        ghostY = secSumY / secondaryW
      }
    }

    val bimodalRatio =
      if (primaryW > 1e-5f) (secondaryW / primaryW).coerceIn(0f, 1f) else 0f
    val modeSeparationM = hypot(ghostX - targetX, ghostY - targetY)
    val hasBimodal = bimodalRatio >= 0.42f && modeSeparationM >= 1.6f

    // Compute local variance around Primary Mode & global variance for confidence
    var varSpatial = 0f
    for (i in 0 until N) {
      val dx = px[i] - targetX
      val dy = py[i] - targetY
      varSpatial += pw[i] * (dx * dx + dy * dy)
    }
    val safeVar = if (varSpatial.isFinite()) varSpatial.coerceAtLeast(0f) else 25f
    val confidence = (100f * exp(-safeVar / 6.2f)).coerceIn(3f, 99.5f)

    val dxTarget = targetX - userX
    val dyTarget = targetY - userY
    val estDist = hypot(dxTarget, dyTarget).coerceAtLeast(0.05f)

    val worldAngleRad = atan2(dxTarget, dyTarget)
    var relRad = worldAngleRad - userAzimuthRad
    while (relRad > PI.toFloat()) relRad -= 2f * PI.toFloat()
    while (relRad < -PI.toFloat()) relRad += 2f * PI.toFloat()
    val relDeg = Math.toDegrees(relRad.toDouble()).toFloat()

    // Orthogonal Step Advisor: direct user toward the axis connecting Primary & Ghost modes to break symmetry
    val stepAdvisorRad =
      if (hasBimodal) {
        atan2(targetX - ghostX, targetY - ghostY)
      } else {
        worldAngleRad
      }
    val stepHintText =
      if (hasBimodal) {
        "检测到双峰镜像对称 (${(bimodalRatio * 100).toInt()}%) · 建议沿紫色引导箭头横移1~2步破缺"
      } else if (confidence >= 70f) {
        "单峰高置信锁定 · 沿橙色准星方向直行逼近"
      } else {
        "概率云坍缩中 · 建议向侧方迈步或原地转身360°增强收敛"
      }

    snapshotVersion++
    snap.estTargetX = targetX
    snap.estTargetY = targetY
    snap.ghostTargetX = ghostX
    snap.ghostTargetY = ghostY
    snap.bimodalRatio = bimodalRatio
    snap.hasBimodalAmbiguity = hasBimodal
    snap.recommendedStepRad = stepAdvisorRad
    snap.orthogonalStepHint = stepHintText
    snap.estDistanceM = estDist
    snap.relativeBearingDeg = relDeg
    snap.confidencePct = confidence
    snap.nEff = nEff
    snap.meanPathLossN = meanN
    snap.resampleCount = resampleCount
    snap.version = snapshotVersion
    return snap
  }
}
