package com.recon.dash.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.data.FavoritePlace
import com.recon.dash.data.FavoriteRepository
import com.recon.dash.data.FavoriteSlot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SavedPlacesViewModel @Inject constructor(
    private val favoriteRepo: FavoriteRepository,
) : ViewModel() {

    /** All saved places keyed by slot (includes Home and Office). */
    val allPlaces = favoriteRepo.observeAll()
        .map { list -> list.associateBy { it.slot } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Custom places only (for the tile-grid entry point). */
    val customPlaces = favoriteRepo.observeAll()
        .map { list ->
            list.filter { it.slot != FavoriteSlot.HOME && it.slot != FavoriteSlot.OFFICE }
                .associateBy { it.slot }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun clear(slot: FavoriteSlot) {
        viewModelScope.launch { favoriteRepo.delete(slot) }
    }

    /**
     * Update the display name and/or icon of an existing saved place. No-op if the slot isn't set
     * yet (you set a place's location first via search, then customize name/icon here).
     */
    fun updateNameAndIcon(slot: FavoriteSlot, name: String, iconKey: String) {
        viewModelScope.launch {
            val existing = favoriteRepo.getBySlot(slot).getOrNull() ?: return@launch
            favoriteRepo.save(
                existing.copy(
                    name = name.ifBlank { existing.name },
                    icon = iconKey,
                )
            )
        }
    }
}
