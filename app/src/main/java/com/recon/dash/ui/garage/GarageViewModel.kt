package com.recon.dash.ui.garage

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.dash.nav.GeoPoint
import com.recon.dash.data.FuelFillup
import com.recon.dash.data.GarageRepository
import com.recon.dash.data.ServiceItem
import com.recon.dash.search.PhotonClient
import com.recon.dash.search.SearchOutcome
import com.recon.dash.search.SearchResult
import com.recon.dash.util.LocationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ServiceStatus { OK, DUE_SOON, OVERDUE }

data class ServiceDisplayItem(
    val item: ServiceItem,
    val status: ServiceStatus,
    val remainingKm: Int,
)

data class FuelStats(
    val avgKml: Double?, // average km/l from last 5 fills
    val last30DaysSpend: Double, // INR spent in last 30 days
)

sealed class NearbyFuelState {
    object Idle : NearbyFuelState()
    object Loading : NearbyFuelState()
    data class Results(val stations: List<SearchResult>) : NearbyFuelState()
    data class Error(val message: String) : NearbyFuelState()
}

@HiltViewModel
class GarageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val garageRepo: GarageRepository,
) : ViewModel() {

    private val _odometer = MutableStateFlow(garageRepo.getOdometer())
    val odometer = _odometer.asStateFlow()

    val fillups = garageRepo.observeRecentFillups(5)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val services = garageRepo.observeServices()
        .combine(_odometer) { items, odo -> items.map { toDisplayItem(it, odo) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _fuelStats = MutableStateFlow(FuelStats(null, 0.0))
    val fuelStats = _fuelStats.asStateFlow()

    private val _nearbyFuel = MutableStateFlow<NearbyFuelState>(NearbyFuelState.Idle)
    val nearbyFuel = _nearbyFuel.asStateFlow()

    private val _showFuelSheet = MutableStateFlow(false)
    val showFuelSheet = _showFuelSheet.asStateFlow()

    init {
        viewModelScope.launch {
            garageRepo.ensureDefaults()
        }
        viewModelScope.launch {
            computeFuelStats()
        }
    }

    fun openFuelSheet() { _showFuelSheet.value = true }
    fun closeFuelSheet() { _showFuelSheet.value = false }

    fun logFillup(litres: Double, costInr: Double, odometerKm: Int) {
        viewModelScope.launch {
            garageRepo.logFillup(litres, costInr, odometerKm)
            _odometer.value = garageRepo.getOdometer()
            computeFuelStats()
            _showFuelSheet.value = false
        }
    }

    fun markServiceDone(itemId: Long, costInr: Double = 0.0) {
        viewModelScope.launch {
            garageRepo.markServiceDone(itemId, costInr)
        }
    }

    fun searchNearbyFuel() {
        _nearbyFuel.value = NearbyFuelState.Loading
        viewModelScope.launch {
            val location = LocationHelper.getLastKnown(context)
            val lat = location?.lat
            val lng = location?.lng

            val outcome = PhotonClient.search(
                query = "petrol pump",
                biasLat = lat,
                biasLng = lng,
            )
            _nearbyFuel.value = when (outcome) {
                is SearchOutcome.Success -> {
                    if (outcome.results.isEmpty()) {
                        NearbyFuelState.Error("No fuel stations found nearby")
                    } else {
                        NearbyFuelState.Results(outcome.results)
                    }
                }
                is SearchOutcome.Failure -> {
                    NearbyFuelState.Error("Search failed. Check internet connection.")
                }
            }
        }
    }

    fun clearNearbyFuel() {
        _nearbyFuel.value = NearbyFuelState.Idle
    }

    private suspend fun computeFuelStats() {
        val recentFills = garageRepo.fillupsSince(0)
            .take(5)
            .filter { it.kml != null }
        val avgKml = if (recentFills.isNotEmpty()) {
            recentFills.mapNotNull { it.kml }.average()
        } else null

        val thirtyDaysAgo = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val last30 = garageRepo.fillupsSince(thirtyDaysAgo)
        val spend = last30.sumOf { it.costInr }

        _fuelStats.value = FuelStats(avgKml = avgKml, last30DaysSpend = spend)
    }

    private fun toDisplayItem(item: ServiceItem, odometer: Int): ServiceDisplayItem {
        val kmSinceDone = odometer - item.lastDoneOdoKm
        val remainingKm = item.intervalKm - kmSinceDone
        val threshold = (item.intervalKm * 0.25).toInt()
        val status = when {
            remainingKm <= 0 -> ServiceStatus.OVERDUE
            remainingKm <= threshold -> ServiceStatus.DUE_SOON
            else -> ServiceStatus.OK
        }
        return ServiceDisplayItem(item, status, remainingKm)
    }
}
