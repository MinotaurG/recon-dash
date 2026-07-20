package com.recon.dash.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.data.FavoritePlace
import com.recon.dash.data.FavoriteRepository
import com.recon.dash.data.FavoriteSlot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val favoriteRepo: FavoriteRepository,
) : ViewModel() {

    val favorites = favoriteRepo.observeAll()
        .map { list -> list.associateBy { it.slot } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _dashConnected = MutableStateFlow(false)
    val dashConnected = _dashConnected.asStateFlow()

    fun setDashConnected(connected: Boolean) {
        _dashConnected.value = connected
    }
}
