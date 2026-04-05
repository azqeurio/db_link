package dev.pl36.cameralink.core.presentation

import dev.pl36.cameralink.core.model.ConnectMode
import dev.pl36.cameralink.core.model.ConnectionMethod
import dev.pl36.cameralink.core.model.FeatureStatus
import dev.pl36.cameralink.core.model.GeoTagLocationSample
import dev.pl36.cameralink.core.session.CameraSessionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

data class SessionPresentation(val title: String, val detail: String)

fun ConnectionMethod.displayLabel(): String = when (this) {
    ConnectionMethod.Wifi -> "Wi-Fi"
    ConnectionMethod.WifiBleAssist -> "Wi-Fi + BT"
    ConnectionMethod.BleOnly -> localized("Bluetooth", "블루투스")
    ConnectionMethod.Unknown -> localized("Offline", "연결 안 됨")
}

fun ConnectMode.displayLabel(): String = when (this) {
    ConnectMode.Play -> localized("Playback", "재생")
    ConnectMode.Record -> localized("Record", "촬영")
    ConnectMode.Shutter -> localized("Shutter", "셔터")
    ConnectMode.Maintenance -> localized("Maintenance", "점검")
    ConnectMode.Unknown -> localized("Checking...", "확인 중...")
}

fun FeatureStatus.displayLabel(): String = when (this) {
    FeatureStatus.Ready -> localized("Ready", "준비 완료")
    FeatureStatus.Partial -> localized("In Progress", "진행 중")
    FeatureStatus.Planned -> localized("Planned", "예정")
}

fun String.asUserFacingFallback(fallback: String): String {
    return if (equals("Unknown", ignoreCase = true) || isBlank()) fallback else this
}

fun CameraSessionState.toPresentation(defaultLiveViewPort: Int): SessionPresentation = when (this) {
    CameraSessionState.Idle -> SessionPresentation(
        localized("Idle", "대기"),
        localized("Waiting for a camera connection", "카메라 연결을 기다리는 중"),
    )
    CameraSessionState.Discovering -> SessionPresentation(
        localized("Searching", "검색 중"),
        localized("Looking for the camera", "카메라를 찾고 있습니다"),
    )
    is CameraSessionState.Connecting -> SessionPresentation(
        localized("Connecting", "연결 중"),
        localized("Connecting over ${method.displayLabel()}", "${method.displayLabel()}로 연결 중"),
    )
    is CameraSessionState.Connected -> SessionPresentation(
        localized("Connected", "연결됨"),
        localized("${mode.displayLabel()} mode", "${mode.displayLabel()} 모드"),
    )
    is CameraSessionState.LiveView -> SessionPresentation(
        "Live View",
        localized("Port ${if (port > 0) port else defaultLiveViewPort}", "포트 ${if (port > 0) port else defaultLiveViewPort}"),
    )
    is CameraSessionState.Transferring -> SessionPresentation(
        localized("Transferring", "가져오는 중"),
        localized("${queueDepth} queued", "${queueDepth}개 대기 중"),
    )
    is CameraSessionState.Error -> SessionPresentation(
        localized("Error", "오류"),
        message,
    )
}

fun CameraSessionState.isConnected(): Boolean =
    this is CameraSessionState.Connected || this is CameraSessionState.LiveView || this is CameraSessionState.Transferring

fun GeoTagLocationSample.coordinateLabel(): String =
    String.format(Locale.getDefault(), "%.5f, %.5f", latitude, longitude)

fun GeoTagLocationSample.accuracyLabel(): String =
    String.format(Locale.getDefault(), "±%.0f m", accuracyMeters)

fun GeoTagLocationSample.altitudeLabel(): String =
    altitude?.let { String.format(Locale.getDefault(), "%.0f m", it) } ?: "--"

fun GeoTagLocationSample.speedLabel(): String = speedMps?.let {
    val kph = it * 3.6f
    if (kph < 1f) "0 km/h" else String.format(Locale.getDefault(), "%.0f km/h", kph)
} ?: "--"

fun GeoTagLocationSample.bearingLabel(): String {
    val deg = bearingDegrees ?: return "--"
    val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return "${dirs[((deg + 22.5f) / 45f).toInt() % 8]} ${deg.toInt()}°"
}

fun GeoTagLocationSample.timeLabel(): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(capturedAtMillis))

fun GeoTagLocationSample.fullTimeLabel(): String =
    DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(capturedAtMillis))

fun GeoTagLocationSample.displayName(): String = placeName ?: coordinateLabel()

fun Int.clockOffsetLabel(): String {
    if (this == 0) return localized("No offset", "오프셋 없음")
    val sign = if (this > 0) "+" else ""
    val absMinutes = abs(this)
    return if (absMinutes < 60) {
        localized("$sign${this} min", "$sign${this}분")
    } else {
        val h = this / 60
        val m = abs(this % 60)
        if (m == 0) {
            localized("$sign${h}h", "$sign${h}시간")
        } else {
            localized("$sign${h}h ${m}m", "$sign${h}시간 ${m}분")
        }
    }
}

private fun localized(english: String, korean: String): String {
    return if (Locale.getDefault().language.equals("ko", ignoreCase = true)) {
        korean
    } else {
        english
    }
}
