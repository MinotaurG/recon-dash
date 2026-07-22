package com.recon.dash.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "fuel_fillups")
data class FuelFillup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: Long, // epoch millis
    val litres: Double,
    val costInr: Double,
    val odometerKm: Int,
    val location: String = "",
    val kml: Double? = null, // calculated from consecutive fills
    val pricePerLitre: Double = 0.0, // derived: costInr / litres
)

@Entity(tableName = "service_items")
data class ServiceItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val intervalKm: Int,
    val lastDoneOdoKm: Int,
    val lastDoneDate: Long, // epoch millis
    val lastCostInr: Double = 0.0,
)

@Dao
interface FuelFillupDao {
    @Query("SELECT * FROM fuel_fillups ORDER BY odometerKm DESC")
    fun observeAll(): Flow<List<FuelFillup>>

    @Query("SELECT * FROM fuel_fillups ORDER BY odometerKm DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FuelFillup>>

    @Query("SELECT * FROM fuel_fillups ORDER BY odometerKm DESC LIMIT 2")
    suspend fun lastTwo(): List<FuelFillup>

    @Query("SELECT * FROM fuel_fillups WHERE date >= :sinceEpoch ORDER BY odometerKm DESC")
    suspend fun since(sinceEpoch: Long): List<FuelFillup>

    @Insert
    suspend fun insert(fillup: FuelFillup): Long

    @Update
    suspend fun update(fillup: FuelFillup)

    @Query("DELETE FROM fuel_fillups WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ServiceItemDao {
    @Query("SELECT * FROM service_items ORDER BY name ASC")
    fun observeAll(): Flow<List<ServiceItem>>

    @Query("SELECT * FROM service_items WHERE id = :id")
    suspend fun getById(id: Long): ServiceItem?

    @Insert
    suspend fun insert(item: ServiceItem): Long

    @Insert
    suspend fun insertAll(items: List<ServiceItem>)

    @Update
    suspend fun update(item: ServiceItem)

    @Query("DELETE FROM service_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM service_items")
    suspend fun count(): Int
}
