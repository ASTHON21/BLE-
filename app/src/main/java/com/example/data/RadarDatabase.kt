package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "calibrated_beacons")
data class CalibratedBeaconEntity(
  @PrimaryKey val macAddress: String,
  val customAlias: String,
  val vendorName: String,
  val calibratedTxPower: Int,
  val calibratedPathLossN: Float,
  val protocolSummary: String,
  val updatedAtMs: Long = System.currentTimeMillis(),
)

@Entity(tableName = "radar_hunt_sessions")
data class RadarHuntSessionEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0L,
  val macAddress: String,
  val deviceName: String,
  val vendorName: String,
  val finalEstX: Float,
  val finalEstY: Float,
  val finalDistM: Float,
  val confidencePct: Float,
  val stepsTaken: Int,
  val resampleCount: Int,
  val obstaclePresetName: String,
  val timestampMs: Long = System.currentTimeMillis(),
)

@Dao
interface RadarDao {
  @Query("SELECT * FROM calibrated_beacons ORDER BY updatedAtMs DESC")
  fun observeCalibratedBeacons(): Flow<List<CalibratedBeaconEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertCalibratedBeacon(entity: CalibratedBeaconEntity)

  @Query("DELETE FROM calibrated_beacons WHERE macAddress = :macAddress")
  suspend fun deleteCalibratedBeacon(macAddress: String)

  @Query("SELECT * FROM radar_hunt_sessions ORDER BY timestampMs DESC LIMIT 50")
  fun observeHuntSessions(): Flow<List<RadarHuntSessionEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertHuntSession(session: RadarHuntSessionEntity)

  @Query("DELETE FROM radar_hunt_sessions WHERE id = :id")
  suspend fun deleteHuntSession(id: Long)

  @Query("DELETE FROM radar_hunt_sessions")
  suspend fun clearAllHuntSessions()
}

@Database(
  entities = [CalibratedBeaconEntity::class, RadarHuntSessionEntity::class],
  version = 1,
  exportSchema = false,
)
abstract class RadarDatabase : RoomDatabase() {
  abstract fun radarDao(): RadarDao

  companion object {
    @Volatile private var instance: RadarDatabase? = null

    fun getInstance(context: Context): RadarDatabase {
      return instance
        ?: synchronized(this) {
          instance
            ?: Room.databaseBuilder(
                context.applicationContext,
                RadarDatabase::class.java,
                "ble_particle_radar.db",
              )
              .fallbackToDestructiveMigration(dropAllTables = true)
              .build()
              .also { instance = it }
        }
    }
  }
}
