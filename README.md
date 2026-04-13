# db link

db link is an Android camera companion app for compatible Olympus and OM SYSTEM cameras.

It provides camera connection, remote capture, live view, image transfer, USB tethering, geotagging, and deep-sky live stacking tools in a modern Android app.

## Goals

- provide a reliable camera-control workflow on Android
- support Wi-Fi and USB/PTP connection paths where the camera exposes them
- keep camera protocol handling separate from UI state and presentation
- keep the main feature areas clear:
  - device onboarding
  - remote capture and live preview
  - image transfer
  - tethered capture
  - geotagging
  - deep-sky live stacking
  - settings and diagnostics

## What Is Implemented

- Kotlin and Jetpack Compose Android app
- adaptive dashboard, remote, transfer, geotagging, settings, and tethering screens
- Wi-Fi camera protocol support for compatible camera CGI endpoints
- USB/PTP support for OM Capture style tethering workflows
- camera property parsing, formatting, and capability mapping
- background image import and local geotagging support
- deep-sky capture and live stacking workflow

## Notes

- camera compatibility depends on the model, firmware, and protocol features exposed by the device
- debug builds include development diagnostics
- release builds hide development-only protocol tooling

## Next Steps

1. broaden device-backed validation across more camera bodies
2. refine USB property coverage where descriptors differ by model
3. improve transfer and tethering recovery flows
4. expand geotagging and deep-sky workflow tests
