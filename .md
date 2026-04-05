# PL36 Camera Link

Android camera control app for compatible Olympus / OM SYSTEM cameras.

This project implements camera control functionality based on observed device behavior and standard communication protocols.

## Windows Path Note

On this machine, open the project from the ASCII-only junction path below instead of the original OneDrive path with Korean characters:

- `C:\dev\pl36-modern-app`

This avoids the Android Gradle Plugin Windows path check that can fail on non-ASCII project paths.

## Goals

- provide a modern single-activity Compose app for camera control
- modernize permissions, storage, Bluetooth, and startup behavior
- isolate camera communication into testable protocol and transport layers
- keep feature coverage clear across:
  - device onboarding
  - remote capture and live preview
  - image transfer
  - firmware-related utilities
  - settings, diagnostics, and location workflows

## What Is Implemented

- Kotlin DSL Android project scaffold
- Compose-first UI shell with adaptive navigation
- custom design system and dashboard-style UI
- protocol models for camera CGI endpoints on the default camera network
- XML command-list parser for `get_commandlist.cgi`
- capability resolver that turns protocol support into product features
- repository and UI scaffolding for camera session workflows
- architecture notes for setup, feature surfaces, and implementation direction

## Notes

- compatibility depends on the camera model and the protocol surface exposed by the device
- debug builds include protocol inspection tools intended for development and testing
- release builds hide development-only protocol tooling

## Next Steps

1. finish QR / BLE onboarding and device discovery
2. complete live preview rendering and camera property writes
3. wire command execution and binary transfer through the network and USB layers
4. expand media import, geotagging, and background work support
5. add more device-backed tests for protocol parsing and recovery behavior
