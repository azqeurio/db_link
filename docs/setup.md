# Setup

## Current project state

This is an actively developed camera-control app foundation rather than a finished product.

Already in place:

- overall app architecture
- modern UI shell
- feature-level screen structure
- protocol parsing and endpoint catalog
- transport and session scaffolding for network and USB camera workflows

Still in progress:

- real device discovery
- full live camera session handling
- complete media transfer pipeline
- firmware upload execution
- expanded device validation across more advanced controls

## Recommended local configuration

### Windows path workaround

If Android Studio reports that the project path contains non-ASCII characters, open the project from this ASCII-only junction instead:

- `C:\dev\pl36-modern-app`

That junction already points to the real workspace location on this machine.

### 1. Install tooling

- Android Studio latest stable
- Android SDK Platform 36
- Android SDK Build-Tools
- Android SDK Platform-Tools
- Android SDK Command-line Tools
- JDK 21 from Android Studio JBR is recommended for this workspace

### 2. Local machine paths

This workspace is currently preconfigured for this machine with:

```properties
sdk.dir=C\:\\Users\\godth\\AppData\\Local\\Android\\Sdk
```

And Gradle is pinned to the Android Studio bundled JBR in:

```properties
org.gradle.java.home=C\:/Program Files/Android/Android Studio/jbr
```

If you move the project to another machine, replace it with:

```properties
sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk
```

If your SDK is installed somewhere else, point `sdk.dir` there instead.

### 3. Open the project

- Open [modern-app](C:/Users/godth/OneDrive/바탕%20화면/pl36/modern-app) in Android Studio
- Let Android Studio sync Gradle
- If prompted, install missing SDK components

### 4. If Gradle wrapper files are missing

This workspace includes:

- `gradlew`
- `gradlew.bat`
- `gradle-wrapper.jar`
- `gradle-wrapper.properties`

Android Studio should be able to sync immediately. If wrapper files ever need regeneration, run the wrapper task with a local Gradle install or use the IDE terminal.

### 5. Useful Android Studio configuration

- Gradle JDK: set to `Android Studio JBR (21)` or another local JDK `21`
- Build and run using: `Gradle`
- Run tests using: `Gradle`
- Enable Compose preview rendering

### 6. Device/emulator recommendations

- Minimum test API: `26`
- Main development target: latest stable API image
- For BLE and camera-adjacent flows, physical-device testing is strongly recommended

## Suggested implementation configuration

### Environment constants

Start with a debug-only configuration layer for:

- camera base URL
- request timeouts
- live-preview default port
- transfer destination collection
- logging level

Suggested shape:

```kotlin
data class AppConfig(
    val cameraBaseUrl: String,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val debugProtocolLogs: Boolean,
)
```

### Build variants

Recommended variants:

- `debug`
  - protocol workbench visible
  - verbose logs on
- `release`
  - workbench hidden
  - minified/shrunk when compatible with the scanner path

### Permissions policy

Request permissions only when entering the relevant feature:

- Devices: nearby devices, location if required by flow
- Remote: camera, microphone where applicable
- Library: photo/video access
- Firmware: notifications if long-running foreground work is used

## Next code steps

1. replace the sample discovery flow with real QR / BLE / Wi-Fi onboarding
2. implement Android 14 partial-photo handling in the `MediaStore` import pipeline
3. wire actual live-preview start/stop and property write CGI calls into `Remote`
4. build a resumable firmware upload executor around chunked camera transfer flows
5. add device-backed integration tests for command-list parsing and connection recovery
