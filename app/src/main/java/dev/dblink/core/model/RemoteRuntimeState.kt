package dev.dblink.core.model

enum class RemoteShootingMode {
    Single,
    Interval,
}

enum class DriveMode(val label: String) {
    Single("Single Shot"),
    Silent("Silent Shot"),
    Burst("Continuous"),
    SilentBurst("Silent Continuous")
}

enum class TimerMode(val label: String) {
    Off("Off"),
    Timer("Timer"),
    BurstTimer("Burst Timer")
}

enum class CameraExposureMode(val label: String) {
    P("P"),
    A("A"),
    S("S"),
    M("M"),
    B("B"),
    VIDEO("Video"),
    ;
}

enum class ModePickerSurface {
    CameraModes,
    Tether,
    TetherRetry,
    DeepSky,
}

/** Which property picker is currently expanded. */
enum class ActivePropertyPicker {
    None,
    ExposureMode,
    Aperture,
    ShutterSpeed,
    Iso,
    WhiteBalance,
    DriveSettings,
    // USB-only properties (controlled via SCP → dial)
    FocusMode,
    Metering,
    Flash,
    ImageQuality,
    UsbDriveMode,
    PictureMode,
    HighRes,
}

/** State for a single camera property with current value and available options. */
data class CameraPropertyValues(
    val currentValue: String = "",
    val availableValues: List<String> = emptyList(),
    val autoActive: Boolean = false,
    val enabled: Boolean = true,
)

/** Touch focus point in normalized coordinates (0.0–1.0). */
data class TouchFocusPoint(
    val x: Float,
    val y: Float,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class TouchFocusRequest(
    val displayX: Float,
    val displayY: Float,
    val focusPlaneX: Float,
    val focusPlaneY: Float,
    val rotationDegrees: Int,
)

data class RemoteRuntimeState(
    val liveViewActive: Boolean = false,
    val focusHeld: Boolean = false,
    val captureCount: Int = 0,
    val exposureCompensation: Float = 0f,
    val exposureCompensationValues: CameraPropertyValues = CameraPropertyValues(),
    val statusLabel: String = "Ready",
    val isBusy: Boolean = false,
    // Shooting mode
    val shootingMode: RemoteShootingMode = RemoteShootingMode.Single,
    // Drive & Timer
    val driveMode: DriveMode = DriveMode.Single,
    val timerMode: TimerMode = TimerMode.Off,
    val timerDelay: Int = 2,
    val driveModeValues: CameraPropertyValues = CameraPropertyValues(),
    // Interval mode
    val intervalSeconds: Int = 5,
    val intervalCount: Int = 10,
    val intervalRunning: Boolean = false,
    val intervalCurrent: Int = 0,
    // Camera property values
    val aperture: CameraPropertyValues = CameraPropertyValues(),
    val shutterSpeed: CameraPropertyValues = CameraPropertyValues(),
    val iso: CameraPropertyValues = CameraPropertyValues(),
    val whiteBalance: CameraPropertyValues = CameraPropertyValues(),
    // Derived exposure mode
    val exposureMode: CameraExposureMode = CameraExposureMode.P,
    val takeMode: CameraPropertyValues = CameraPropertyValues(),
    // Which property picker is open
    val activePicker: ActivePropertyPicker = ActivePropertyPicker.None,
    // Which app-side surface is shown inside the exposure/mode picker
    val modePickerSurface: ModePickerSurface = ModePickerSurface.CameraModes,
    // Whether properties have been loaded from camera
    val propertiesLoaded: Boolean = false,
    // Touch focus
    val touchFocusPoint: TouchFocusPoint? = null,
    val isFocusing: Boolean = false,
)
