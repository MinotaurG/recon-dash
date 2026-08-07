package com.recon.dash.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icon catalog for saved places. Maps a stable string key (persisted in [FavoritePlace.icon]) to a
 * Material rounded icon. Keys are stored, NOT the vector, so the DB stays version-stable.
 *
 * Uses androidx.compose.material.icons.extended (already a dependency) — no new assets.
 */
object PlaceIcons {

    /** Ordered list for the picker grid: (key, icon). */
    val all: List<Pair<String, ImageVector>> = listOf(
        "home" to Icons.Rounded.Home,
        "work" to Icons.Rounded.Work,
        "gym" to Icons.Rounded.FitnessCenter,
        "person" to Icons.Rounded.Person,
        "people" to Icons.Rounded.People,
        "favorite" to Icons.Rounded.Favorite,
        "star" to Icons.Rounded.Star,
        "fuel" to Icons.Rounded.LocalGasStation,
        "charging" to Icons.Rounded.EvStation,
        "food" to Icons.Rounded.Restaurant,
        "cafe" to Icons.Rounded.LocalCafe,
        "bar" to Icons.Rounded.LocalBar,
        "shopping" to Icons.Rounded.ShoppingCart,
        "store" to Icons.Rounded.Store,
        "hospital" to Icons.Rounded.LocalHospital,
        "pharmacy" to Icons.Rounded.LocalPharmacy,
        "school" to Icons.Rounded.School,
        "parking" to Icons.Rounded.LocalParking,
        "hotel" to Icons.Rounded.Hotel,
        "beach" to Icons.Rounded.BeachAccess,
        "mountain" to Icons.Rounded.Terrain,
        "park" to Icons.Rounded.Park,
        "bike" to Icons.Rounded.TwoWheeler,
        "car" to Icons.Rounded.DirectionsCar,
        "garage" to Icons.Rounded.Garage,
        "work_office" to Icons.Rounded.Business,
        "place" to Icons.Rounded.Place,
        "flag" to Icons.Rounded.Flag,
        "pin" to Icons.Rounded.PushPin,
        "location" to Icons.Rounded.MyLocation,
    )

    private val byKey: Map<String, ImageVector> = all.toMap()

    /** Resolve an icon key to a vector, falling back to a generic place pin. */
    fun get(key: String?): ImageVector = key?.let { byKey[it] } ?: Icons.Rounded.Place

    /** The icon for a saved place: its own icon key, else the slot's default. */
    fun forPlace(place: FavoritePlace): ImageVector =
        get(place.icon.ifBlank { place.slot.defaultIconKey() })

    /** The icon for an empty slot (uses the slot's default key). */
    fun forSlot(slot: FavoriteSlot): ImageVector = get(slot.defaultIconKey())
}
