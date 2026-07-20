package com.recon.dash.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.data.RideRecord
import com.recon.dash.data.RideRecordDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RideDetailViewModel @Inject constructor(
    private val dao: RideRecordDao,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val rideId: Long = savedStateHandle.get<String>("rideId")?.toLongOrNull() ?: 0

    private val _ride = MutableStateFlow<RideRecord?>(null)
    val ride = _ride.asStateFlow()

    init {
        viewModelScope.launch {
            _ride.value = dao.getById(rideId)
        }
    }

    fun delete() {
        viewModelScope.launch {
            dao.deleteById(rideId)
        }
    }
}
