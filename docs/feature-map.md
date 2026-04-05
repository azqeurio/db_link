# Feature Surfaces

## Purpose

This document describes the main product surfaces in the app and how they map to shared protocol and platform layers.

## Product areas

- `Devices`
  - QR onboarding, Wi-Fi/BLE setup, saved camera management, and connection status

- `Remote`
  - live preview, shutter actions, focus controls, exposure controls, and movie-related actions

- `Library`
  - media lists, thumbnails, transfer presets, import queues, and save strategy

- `Firmware`
  - version checks, package staging, upload flow, and apply / reboot confirmation

- `Location`
  - place history, map views, time matching, privacy controls, and export support

- `Settings`
  - defaults, privacy, diagnostics, import preferences, and remote preferences

- `Dashboard`
  - overview, guided entry points, connection health, and development tools

## Architecture rules

- each feature surface should align with a user intent
- protocol access should be shared across screens
- capability parsing decides which controls render
- storage, permissions, and background work should follow modern Android behavior
