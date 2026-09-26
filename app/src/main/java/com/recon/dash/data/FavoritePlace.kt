package com.recon.dash.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

enum class FavoriteSlot {
    HOME,
    OFFICE,
    // Preset custom slots. Home & Office stay pinned on the home screen; these are the
    // user-facing presets in Saved Places. Each has a default icon + a preset display name that
    // the user can override (e.g. FRIEND_1 -> "Rahul"). CUSTOM is a fully free name + icon.
    GYM,
    FRIEND_1,
    FRIEND_2,
    FUEL,
    FOOD,
    CUSTOM,
}

/** The preset custom slots shown in Saved Places (Home/Office are pinned separately). */
val CUSTOM_SLOTS = listOf(
    FavoriteSlot.GYM,
    FavoriteSlot.FRIEND_1,
    FavoriteSlot.FRIEND_2,
    FavoriteSlot.FUEL,
    FavoriteSlot.FOOD,
    FavoriteSlot.CUSTOM,
)

/** Default preset display name for a slot (user can override via [FavoritePlace.name]). */
fun FavoriteSlot.presetName(): String = when (this) {
    FavoriteSlot.HOME -> "Home"
    FavoriteSlot.OFFICE -> "Office"
    FavoriteSlot.GYM -> "Gym"
    FavoriteSlot.FRIEND_1 -> "Friend 1"
    FavoriteSlot.FRIEND_2 -> "Friend 2"
    FavoriteSlot.FUEL -> "Fuel"
    FavoriteSlot.FOOD -> "Food"
    FavoriteSlot.CUSTOM -> "Custom"
}

/** Default icon key for a slot (see [PlaceIcons]); user can override via [FavoritePlace.icon]. */
fun FavoriteSlot.defaultIconKey(): String = when (this) {
    FavoriteSlot.HOME -> "home"
    FavoriteSlot.OFFICE -> "work"
    FavoriteSlot.GYM -> "gym"
    FavoriteSlot.FRIEND_1 -> "person"
    FavoriteSlot.FRIEND_2 -> "person"
    FavoriteSlot.FUEL -> "fuel"
    FavoriteSlot.FOOD -> "food"
    FavoriteSlot.CUSTOM -> "star"
}

@Entity(tableName = "favorite_places")
data class FavoritePlace(
    @PrimaryKey
    val slot: FavoriteSlot,
    val name: String,
    val label: String,
    val lat: Double,
    val lng: Double,
    val address: String = "",
    /** Icon key from [PlaceIcons]; blank falls back to the slot's default icon. */
    val icon: String = "",
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
