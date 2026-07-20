package com.recon.dash.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "ride_records")
data class RideRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long = 0,
    val distanceMeters: Double = 0.0,
    val durationSeconds: Long = 0,
    val avgSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val destinationName: String = "",
    val startLat: Double = 0.0,
    val startLng: Double = 0.0,
    val endLat: Double = 0.0,
    val endLng: Double = 0.0,
    val encodedPolyline: String = "",
)

@Dao
interface RideRecordDao {
    @Query("SELECT * FROM ride_records ORDER BY startTime DESC")
    fun observeAll(): Flow<List<RideRecord>>

    @Query("SELECT * FROM ride_records ORDER BY startTime DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RideRecord>>

    @Query("SELECT * FROM ride_records WHERE id = :id")
    suspend fun getById(id: Long): RideRecord?

    @Insert
    suspend fun insert(record: RideRecord): Long

    @Update
    suspend fun update(record: RideRecord)

    @Query("DELETE FROM ride_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM ride_records")
    suspend fun count(): Int

    @Query("SELECT SUM(distanceMeters) FROM ride_records")
    suspend fun totalDistance(): Double?
}
