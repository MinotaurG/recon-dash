package com.recon.dash.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for garage data: fuel fillups, service tracker, and odometer.
 * The odometer is persisted in SharedPreferences for simplicity (single int value).
 */
@Singleton
class GarageRepository @Inject constructor(
    private val fuelDao: FuelFillupDao,
    private val serviceDao: ServiceItemDao,
    private val odometerStore: OdometerStore,
) {
    // ── Fuel ────────────────────────────────────────────────────────────────────

    fun observeFillups(): Flow<List<FuelFillup>> = fuelDao.observeAll()

    fun observeRecentFillups(limit: Int = 5): Flow<List<FuelFillup>> =
        fuelDao.observeRecent(limit)

    suspend fun logFillup(litres: Double, costInr: Double, odometerKm: Int, location: String = "") {
        // Calculate km/l from the previous fill
        val previous = fuelDao.lastTwo().firstOrNull()
        val kml = if (previous != null && odometerKm > previous.odometerKm) {
            val kmDriven = odometerKm - previous.odometerKm
            kmDriven.toDouble() / litres
        } else {
            null
        }

        val fillup = FuelFillup(
            date = System.currentTimeMillis(),
            litres = litres,
            costInr = costInr,
            odometerKm = odometerKm,
            location = location,
            kml = kml,
        )
        fuelDao.insert(fillup)

        // Update odometer if this fill has a higher reading
        if (odometerKm > odometerStore.get()) {
            odometerStore.set(odometerKm)
        }
    }

    suspend fun fillupsSince(epochMillis: Long): List<FuelFillup> =
        fuelDao.since(epochMillis)

    // ── Service ─────────────────────────────────────────────────────────────────

    fun observeServices(): Flow<List<ServiceItem>> = serviceDao.observeAll()

    suspend fun markServiceDone(itemId: Long, costInr: Double = 0.0) {
        val item = serviceDao.getById(itemId) ?: return
        serviceDao.update(
            item.copy(
                lastDoneOdoKm = odometerStore.get(),
                lastDoneDate = System.currentTimeMillis(),
                lastCostInr = costInr,
            )
        )
    }

    suspend fun ensureDefaults() {
        if (serviceDao.count() > 0) return
        serviceDao.insertAll(HIMALAYAN_450_DEFAULTS)
    }

    // ── Odometer ────────────────────────────────────────────────────────────────

    fun getOdometer(): Int = odometerStore.get()

    fun setOdometer(km: Int) = odometerStore.set(km)

    companion object {
        val HIMALAYAN_450_DEFAULTS = listOf(
            ServiceItem(name = "Engine oil", intervalKm = 10000, lastDoneOdoKm = 0, lastDoneDate = 0),
            ServiceItem(name = "Oil filter", intervalKm = 10000, lastDoneOdoKm = 0, lastDoneDate = 0),
            ServiceItem(name = "Air filter", intervalKm = 10000, lastDoneOdoKm = 0, lastDoneDate = 0),
            ServiceItem(name = "Chain lube", intervalKm = 500, lastDoneOdoKm = 0, lastDoneDate = 0),
            ServiceItem(name = "Brake pads inspect", intervalKm = 10000, lastDoneOdoKm = 0, lastDoneDate = 0),
            ServiceItem(name = "Tyre inspect", intervalKm = 10000, lastDoneOdoKm = 0, lastDoneDate = 0),
        )
    }
}

/**
 * Simple persisted odometer value. A single int stored in SharedPreferences.
 */
@Singleton
class OdometerStore @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences("garage", Context.MODE_PRIVATE)

    fun get(): Int = prefs.getInt(KEY_ODO, 0)

    fun set(km: Int) = prefs.edit().putInt(KEY_ODO, km).apply()

    private companion object {
        const val KEY_ODO = "odometer_km"
    }
}
