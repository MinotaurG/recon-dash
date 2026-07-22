package com.recon.dash.ui.search

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.data.FavoritePlace
import com.recon.dash.data.FavoriteRepository
import com.recon.dash.data.FavoriteSlot
import com.recon.dash.search.PhotonClient
import com.recon.dash.search.RecentSearchStore
import com.recon.dash.search.SearchError
import com.recon.dash.search.SearchOutcome
import com.recon.dash.search.SearchResult
import com.recon.dash.util.LocationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val favoriteRepo: FavoriteRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val saveToSlot: FavoriteSlot? = savedStateHandle.get<String>("saveSlot")
        ?.let { runCatching { FavoriteSlot.valueOf(it) }.getOrNull() }

    private val recentStore = RecentSearchStore(context)

    private val _recents = MutableStateFlow(recentStore.get())
    val recents = _recents.asStateFlow()

    /** Record a selected destination so it appears in recent searches next time. */
    fun recordSelection(result: SearchResult) {
        recentStore.add(result)
        _recents.value = recentStore.get()
    }

    fun saveAsFavorite(result: SearchResult, slot: FavoriteSlot, label: String) {
        viewModelScope.launch {
            favoriteRepo.save(
                FavoritePlace(
                    slot = slot,
                    name = result.name,
                    label = label,
                    lat = result.location.lat,
                    lng = result.location.lng,
                    address = result.address,
                )
            )
        }
    }

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results = _results.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private var searchJob: Job? = null

    fun updateQuery(value: String) {
        _query.value = value
        _error.value = null
        searchJob?.cancel()

        if (value.trim().length < 2) {
            _results.value = emptyList()
            _isLoading.value = false
            return
        }

        searchJob = viewModelScope.launch {
            delay(400) // debounce
            _isLoading.value = true
            val loc = LocationHelper.getLastKnown(context)
            val outcome = PhotonClient.search(value, loc?.lat, loc?.lng)
            // Guard against stale responses: only apply if the query still matches.
            if (_query.value != value) return@launch
            when (outcome) {
                is SearchOutcome.Success -> {
                    _results.value = outcome.results
                    _error.value = null
                }
                is SearchOutcome.Failure -> {
                    _results.value = emptyList()
                    _error.value = when (outcome.error) {
                        is SearchError.NetworkFailed -> "Network error, check connection"
                        is SearchError.ParseFailed -> "Unexpected response"
                        SearchError.EmptyQuery -> null
                    }
                }
            }
            _isLoading.value = false
        }
    }
}
