@file:Suppress("ConstPropertyName")

package dev.pl36.cameralink.core.protocol

import dev.pl36.cameralink.core.model.CameraEndpointDefinition

object CameraProtocolCatalog {
    const val BaseUrl = "http://192.168.0.10"

    val endpoints: List<CameraEndpointDefinition> = listOf(
        CameraEndpointDefinition("Session", "/get_connectmode.cgi", "Get connect mode", "Use for health checks and mode-aware routing."),
        CameraEndpointDefinition("Session", "/get_commandlist.cgi", "Get command list", "Parse once and gate unsupported features from the UI."),
        CameraEndpointDefinition("Session", "/switch_cammode.cgi?mode=rec&lvqty=", "Switch to record mode", "Treat as explicit state-machine transition."),
        CameraEndpointDefinition("Session", "/switch_cammode.cgi?mode=play", "Switch to playback mode", "Keep playback browsing separate from capture workflows."),
        CameraEndpointDefinition("Remote", "/exec_takemotion.cgi?com=takeready", "Half-press equivalent", "Drive focus readiness and user feedback from a single source of truth."),
        CameraEndpointDefinition("Remote", "/exec_takemotion.cgi?com=starttake", "Take still image", "Represent capture as an intent, not a raw button side effect."),
        CameraEndpointDefinition("Remote", "/exec_shutter.cgi?com=1stpush", "Press first stage", "Only expose when the camera actually supports staged shutter flows."),
        CameraEndpointDefinition("Remote", "/exec_takemisc.cgi?com=startliveview&port=", "Start live view", "Move live-view transport wiring behind one coordinator."),
        CameraEndpointDefinition("Remote", "/exec_takemisc.cgi?com=stopliveview", "Stop live view", "Ensure teardown happens through lifecycle-aware state."),
        CameraEndpointDefinition("Properties", "/get_camprop.cgi?com=check&propname=", "Get property state", "Use capability-aware property descriptors rather than stringly UI."),
        CameraEndpointDefinition("Properties", "/set_camprop.cgi?com=set&propname=", "Set property state", "Validate and debounce property writes."),
        CameraEndpointDefinition("Transfer", "/get_imglist.cgi?DIR=/DCIM", "Fetch media list", "Load pages lazily and cache metadata separately from thumbnails."),
        CameraEndpointDefinition("Transfer", "/get_thumbnail.cgi?DIR=", "Fetch thumbnail", "Use progressive image loading and cancel off-screen requests."),
        CameraEndpointDefinition("Transfer", "/get_screennail.cgi?DIR=", "Fetch screennail", "Use for fast preview surfaces while full media is pending."),
        CameraEndpointDefinition("Transfer", "/get_resizeimg_witherr.cgi?DIR=", "Fetch resized image", "Prefer camera-side resize for quick mobile transfer presets."),
        CameraEndpointDefinition("Streaming", "/ready_moviestream.cgi?videomethod=udp&videoport=", "Prepare movie stream", "Keep transport negotiation out of UI composables."),
        CameraEndpointDefinition("Streaming", "/start_moviestream.cgi?DIR=", "Start movie stream", "Treat movie playback as a dedicated session."),
        CameraEndpointDefinition("Firmware", "/fwup_getversions.cgi", "Fetch firmware versions", "Drive update prompts from explicit version comparison."),
        CameraEndpointDefinition("Firmware", "/fwup_sendinfo.cgi?ObjectCompressSize=%d", "Begin firmware upload", "Model upload progress as resumable chunks."),
        CameraEndpointDefinition("Firmware", "/fwup_sendsplit.cgi?OffsetPos=%d&Byte=%d", "Send firmware chunk", "Retry idempotently on chunk boundaries."),
        CameraEndpointDefinition("Firmware", "/fwup_update.cgi", "Apply firmware", "Guard with charge, connectivity, and camera state checks."),
        CameraEndpointDefinition("Diagnostics", "/get_cameraloginfo.cgi", "Fetch camera log metadata", "Push diagnostics behind a support-only workflow."),
        CameraEndpointDefinition("Myset", "/request_getmysetdata.cgi?mode=%s&kind=current", "Start myset export", "Use a binary adapter layer instead of leaking protocol state to UI."),
        CameraEndpointDefinition("Myset", "/send_partialmysetdata.cgi?offset=%d&size=%d", "Upload myset chunk", "Resume chunk uploads safely with progress checkpoints."),
    )
}
