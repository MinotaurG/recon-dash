package com.recon.dash.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class FavoriteSlot {
    HOME,
    OFFICE,
    CUSTOM_1,
    CUSTOM_2,
    CUSTOM_3,
    CUSTOM_4,
}

val CUSTOM_SLOTS = listOf(
    FavoriteSlot.CUSTOM_1,
    FavoriteSlot.CUSTOM_2,
    FavoriteSlot.CUSTOM_3,
    FavoriteSlot.CUSTOM_4,
)

@Entity(tableName = "favorite_places")
data class FavoritePlace(
    @PrimaryKey
    val slot: FavoriteSlot,
    val name: String,
    val label: String,
    val lat: Double,
    val lng: Double,
    val address: String = "",
)

@Dao
interface FavoritePlaceDao {
    @Query("SELECT * FROM favorite_places ORDER BY slot ASC")
    fun observeAll(): Flow<List<FavoritePlace>>

    @Query("SELECT * FROM favorite_places WHERE slot = :slot")
    suspend fun getBySlot(slot: FavoriteSlot): FavoritePlace?

    @Upsert
    suspend fun upsert(place: FavoritePlace)

    @Query("DELETE FROM favorite_places WHERE slot = :slot")
    suspend fun deleteBySlot(slot: FavoriteSlot)

    @Query("SELECT COUNT(*) FROM favorite_places")
    suspend fun count(): Int
}
