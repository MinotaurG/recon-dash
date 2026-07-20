package com.recon.dash.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.data.RideRecord
import com.recon.dash.data.RideRecordDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RideHistoryViewModel @Inject constructor(
    private val dao: RideRecordDao,
) : ViewModel() {

    val rides = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _totalKm = MutableStateFlow(0.0)
    val totalKm = _totalKm.asStateFlow()

    init {
        viewModelScope.launch {
            _totalKm.value = (dao.totalDistance() ?: 0.0) / 1000.0
        }
    }
}
