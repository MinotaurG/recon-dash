package com.recon.dash.dash

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide holder for the current dash connection state, so screens that don't
 * own the DashSession (e.g. ActiveNavScreen, HomeScreen) can observe whether the
 * dash is connected/streaming. Updated by DashViewModel as the session progresses.
 */
object DashConnectionState {
    private val _state = MutableStateFlow(DashState.IDLE)
    val state = _state.asStateFlow()

    fun update(state: DashState) {
        _state.value = state
    }

    val isConnected: Boolean
        get() = _state.value == DashState.READY || _state.value == DashState.STREAMING
}
