package com.example.engine

import com.example.model.NlosScenarioPreset
import com.example.model.WallObstacle

/**
 * Interactive NLOS Indoor Multipath Obstacle Simulator:
 * Computes exact 2D line-segment intersections between user position (ux, uy) and beacon (bx, by)
 * across concrete/metal partition walls to simulate realistic indoor Non-Line-Of-Sight (NLOS) attenuation.
 */
object NlosSandboxSimulator {

  fun getObstaclesForPreset(preset: NlosScenarioPreset): List<WallObstacle> {
    return when (preset) {
      NlosScenarioPreset.LOS_OPEN -> emptyList()
      NlosScenarioPreset.SINGLE_WALL ->
        listOf(
          WallObstacle(
            id = "wall_main",
            label = "承重混凝土隔墙 (-6.5dB)",
            x1 = -4.8f,
            y1 = -0.8f,
            x2 = 4.8f,
            y2 = -0.8f,
            attenuationDb = 6.5f,
          )
        )
      NlosScenarioPreset.MULTI_CORRIDOR ->
        listOf(
          WallObstacle(
            id = "wall_south",
            label = "南侧砖混墙 (-5.5dB)",
            x1 = -5.2f,
            y1 = -1.0f,
            x2 = 5.2f,
            y2 = -1.0f,
            attenuationDb = 5.5f,
          ),
          WallObstacle(
            id = "wall_east_partition",
            label = "金属机柜隔断 (-6.0dB)",
            x1 = 1.1f,
            y1 = -4.8f,
            x2 = 1.1f,
            y2 = 4.5f,
            attenuationDb = 6.0f,
          ),
        )
    }
  }

  /**
   * Calculates total NLOS wall attenuation (dB) along the ray from (ux, uy) to (bx, by).
   */
  fun computeWallAttenuationDb(
    ux: Float,
    uy: Float,
    bx: Float,
    by: Float,
    obstacles: List<WallObstacle>,
  ): Float {
    if (obstacles.isEmpty()) return 0f
    var totalLoss = 0f
    for (wall in obstacles) {
      if (segmentsIntersect(ux, uy, bx, by, wall.x1, wall.y1, wall.x2, wall.y2)) {
        totalLoss += wall.attenuationDb
      }
    }
    return totalLoss
  }

  private fun segmentsIntersect(
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    cx: Float,
    cy: Float,
    dx: Float,
    dy: Float,
  ): Boolean {
    val d1 = crossProduct(cx, cy, dx, dy, ax, ay)
    val d2 = crossProduct(cx, cy, dx, dy, bx, by)
    val d3 = crossProduct(ax, ay, bx, by, cx, cy)
    val d4 = crossProduct(ax, ay, bx, by, dx, dy)
    return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) &&
      ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
  }

  private fun crossProduct(
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    px: Float,
    py: Float,
  ): Float {
    return (bx - ax) * (py - ay) - (by - ay) * (px - ax)
  }
}
