package com.recon.dash.ui.obd

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.obd.Elm327Source
import com.recon.dash.obd.EngineState
import com.recon.dash.obd.TelemetryStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the cyberpunk cluster from a LIVE ELM327 OBD-II dongle. No replay/demo data — if the
 * dongle isn't reachable the UI shows a prompt (enable Bluetooth / connect the OBD module) based
 * on [reason].
 */
@HiltViewModel
class ClusterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val source = Elm327Source(context)

    val engine = source.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), EngineState.EMPTY)
    val status = source.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), TelemetryStatus.DISCONNECTED)
    val reason = source.reason
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), Elm327Source.Reason.NONE)

    private var connectJob: Job? = null

    init { connect() }

    /** (Re)attempt the dongle connection — call after the user enables Bluetooth / pairs the OBD. */
    fun connect() {
        connectJob?.cancel()
        source.stop()
        connectJob = viewModelScope.launch { source.start() }
    }

    override fun onCleared() {
        source.stop()
        super.onCleared()
    }
}
