package com.recon.dash.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * One captured comparison of an on-device Valhalla route vs a Google reference route for the same
 * origin→destination. Debug-only tuning data (see GoogleRoutesClient / RouteComparator): exported
 * with the logs and analysed offline to tune Valhalla costing. Encoded polylines are stored so the
 * exact divergent geometry can be reconstructed later. Never read by the live nav path.
 *
 * @param context "plan" (initial route), "reroute", or "periodic" (mid-ride tick).
 */
@Entity(tableName = "route_divergences")
data class RouteDivergenceRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val context: String,
    val originLat: Double,
    val originLng: Double,
    val destLat: Double,
    val destLng: Double,
    val overlapPct: Double,
    val valhallaMeters: Double,
    val googleMeters: Double,
    val valhallaSeconds: Double,
    val googleSeconds: Double,
    val valhallaPolyline: String,
    val googlePolyline: String,
    val appVersion: String = "",
)

@Dao
interface RouteDivergenceDao {
    @Insert
    suspend fun insert(record: RouteDivergenceRecord): Long

    @Query("SELECT * FROM route_divergences ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<RouteDivergenceRecord>>

    @Query("SELECT * FROM route_divergences ORDER BY timestamp DESC")
    suspend fun getAll(): List<RouteDivergenceRecord>

    @Query("SELECT COUNT(*) FROM route_divergences")
    suspend fun count(): Int

    @Query("SELECT AVG(overlapPct) FROM route_divergences")
    suspend fun avgOverlap(): Double?
}
