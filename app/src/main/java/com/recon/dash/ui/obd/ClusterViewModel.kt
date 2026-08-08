package com.recon.dash.ui.obd

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.recon.dash.obd.BrcReplaySource
import com.recon.dash.obd.EngineState
import com.recon.dash.obd.TelemetrySource
import com.recon.dash.obd.TelemetryStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the cyberpunk cluster. For now it runs the [BrcReplaySource] (real recorded ride) so the
 * cluster is fully alive without the bike; swapping to the live ELM327 dongle is a one-line source
 * change here once [com.recon.dash.obd.Elm327Source] lands.
 */
@HiltViewModel
class ClusterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // TODO(dongle): switch to Elm327Source when live OBD is wired; UI/downstream is unchanged.
    private val source: TelemetrySource = BrcReplaySource(context, loop = true)

    val engine = source.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), EngineState.EMPTY)
    val status = source.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), TelemetryStatus.DISCONNECTED)

    init {
        viewModelScope.launch { source.start() }
    }

    override fun onCleared() {
        source.stop()
        super.onCleared()
    }
}
