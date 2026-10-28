package com.example.data

import kotlinx.coroutines.flow.Flow

class RadarRepository(private val radarDao: RadarDao) {
  val calibratedBeacons: Flow<List<CalibratedBeaconEntity>> = radarDao.observeCalibratedBeacons()
  val huntSessions: Flow<List<RadarHuntSessionEntity>> = radarDao.observeHuntSessions()

  suspend fun saveCalibration(entity: CalibratedBeaconEntity) {
    radarDao.upsertCalibratedBeacon(entity)
  }

  suspend fun deleteCalibration(macAddress: String) {
    radarDao.deleteCalibratedBeacon(macAddress)
  }

  suspend fun recordHuntSession(session: RadarHuntSessionEntity) {
    radarDao.insertHuntSession(session)
  }

  suspend fun deleteHuntSession(id: Long) {
    radarDao.deleteHuntSession(id)
  }

  suspend fun clearHuntSessions() {
    radarDao.clearAllHuntSessions()
  }
}
