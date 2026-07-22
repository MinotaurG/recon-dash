package com.recon.dash.dash

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object TelemetryBus {
    private val _packets = MutableSharedFlow<TelemetryPacket>(extraBufferCapacity = 64)
    val packets = _packets.asSharedFlow()

    fun emit(packet: TelemetryPacket) {
        _packets.tryEmit(packet)
    }
}
