package dev.pl36.cameralink.core.session

import dev.pl36.cameralink.core.logging.D
import dev.pl36.cameralink.core.model.ConnectMode
import dev.pl36.cameralink.core.model.ConnectionMethod

sealed interface CameraSessionState {
    data object Idle : CameraSessionState
    data object Discovering : CameraSessionState
    data class Connecting(val method: ConnectionMethod) : CameraSessionState
    data class Connected(val method: ConnectionMethod, val mode: ConnectMode) : CameraSessionState
    data class LiveView(val port: Int) : CameraSessionState
    data class Transferring(val queueDepth: Int) : CameraSessionState
    data class Error(val message: String) : CameraSessionState
}

sealed interface CameraSessionEvent {
    data object StartDiscovery : CameraSessionEvent
    data class BeginConnect(val method: ConnectionMethod) : CameraSessionEvent
    data class Connected(val method: ConnectionMethod, val mode: ConnectMode) : CameraSessionEvent
    data class StartLiveView(val port: Int) : CameraSessionEvent
    data class StartTransfer(val queueDepth: Int) : CameraSessionEvent
    data class Fail(val message: String) : CameraSessionEvent
    data object Reset : CameraSessionEvent
}

class CameraSessionStateMachine {
    fun reduce(
        current: CameraSessionState,
        event: CameraSessionEvent,
    ): CameraSessionState {
        val next = when (event) {
            CameraSessionEvent.StartDiscovery -> CameraSessionState.Discovering
            is CameraSessionEvent.BeginConnect -> CameraSessionState.Connecting(event.method)
            is CameraSessionEvent.Connected -> CameraSessionState.Connected(event.method, event.mode)
            is CameraSessionEvent.StartLiveView -> CameraSessionState.LiveView(event.port)
            is CameraSessionEvent.StartTransfer -> CameraSessionState.Transferring(event.queueDepth)
            is CameraSessionEvent.Fail -> CameraSessionState.Error(event.message)
            CameraSessionEvent.Reset -> CameraSessionState.Idle
        }
        D.session("$current --[${event::class.simpleName}]--> $next")
        return next
    }
}
