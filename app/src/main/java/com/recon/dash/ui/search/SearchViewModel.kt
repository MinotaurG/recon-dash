package com.recon.dash.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.data.FavoritePlace
import com.recon.dash.data.FavoriteRepository
import com.recon.dash.data.FavoriteSlot
import com.recon.dash.search.PhotonClient
import com.recon.dash.search.SearchError
import com.recon.dash.search.SearchOutcome
import com.recon.dash.search.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val favoriteRepo: FavoriteRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val saveToSlot: FavoriteSlot? = savedStateHandle.get<String>("saveSlot")
        ?.let { runCatching { FavoriteSlot.valueOf(it) }.getOrNull() }

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
            return
        }

        searchJob = viewModelScope.launch {
            delay(350) // debounce
            _isLoading.value = true
            when (val outcome = PhotonClient.search(value)) {
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
