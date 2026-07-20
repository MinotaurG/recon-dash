package com.recon.dash.ui.route

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.dash.nav.GeoPoint
import com.recon.dash.dash.nav.ManeuverType
import com.recon.dash.dash.nav.Router
import com.recon.dash.dash.nav.RouterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RoutePreviewState {
    object Loading : RoutePreviewState()
    object NoGraph : RoutePreviewState()
    data class Ready(
        val destinationName: String,
        val distanceText: String,
        val etaText: String,
        val turnCount: Int,
        val maneuvers: List<String>,
    ) : RoutePreviewState()
    data class Error(val message: String) : RoutePreviewState()
}

@HiltViewModel
class RoutePreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow<RoutePreviewState>(RoutePreviewState.Loading)
    val state = _state.asStateFlow()

    private val router = Router(context)

    private val destName: String = savedStateHandle.get<String>("destName") ?: "Destination"
    private val destLat: Double = savedStateHandle.get<String>("destLat")?.toDoubleOrNull() ?: 0.0
    private val destLng: Double = savedStateHandle.get<String>("destLng")?.toDoubleOrNull() ?: 0.0
    private val originLat: Double = savedStateHandle.get<String>("originLat")?.toDoubleOrNull() ?: 0.0
    private val originLng: Double = savedStateHandle.get<String>("originLng")?.toDoubleOrNull() ?: 0.0

    init {
        calculateRoute()
    }

    fun retry() {
        calculateRoute()
    }

    private fun calculateRoute() {
        _state.value = RoutePreviewState.Loading
        viewModelScope.launch {
            if (!router.graphExists()) {
                _state.value = RoutePreviewState.NoGraph
                return@launch
            }

            val loadResult = router.load()
            if (loadResult.isFailure) {
                _state.value = RoutePreviewState.Error(
                    "Failed to load routing graph — ${loadResult.exceptionOrNull()?.message}"
                )
                return@launch
            }

            val from = GeoPoint(originLat, originLng)
            val to = GeoPoint(destLat, destLng)

            when (val result = router.route(from, to)) {
                is RouterResult.Success -> {
                    val route = result.route
                    _state.value = RoutePreviewState.Ready(
                        destinationName = destName,
                        distanceText = formatDistance(route.totalMeters),
                        etaText = formatEta(route.totalSeconds),
                        turnCount = route.maneuvers.count {
                            it.type != ManeuverType.DEPART && it.type != ManeuverType.CONTINUE
                        },
                        maneuvers = route.maneuvers
                            .filter { it.type != ManeuverType.DEPART }
                            .map { it.instruction },
                    )
                }
                is RouterResult.Failure -> {
                    val msg = when (val err = result.error) {
                        is com.recon.dash.dash.nav.RouterError.GraphNotLoaded -> err.message
                        is com.recon.dash.dash.nav.RouterError.NoRouteFound -> "No route found to destination"
                        is com.recon.dash.dash.nav.RouterError.RoutingFailed -> "Routing error — ${err.cause.message}"
                    }
                    _state.value = RoutePreviewState.Error(msg)
                }
            }
        }
    }

    override fun onCleared() {
        router.release()
        super.onCleared()
    }

    private fun formatDistance(meters: Double): String = when {
        meters >= 1000 -> "%.1f km".format(meters / 1000.0)
        else -> "${meters.toInt()} m"
    }

    private fun formatEta(seconds: Double): String {
        val mins = (seconds / 60).toInt()
        return when {
            mins >= 60 -> "${mins / 60}h ${mins % 60}m"
            else -> "${mins} min"
        }
    }
}
