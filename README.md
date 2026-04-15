# db link

db link is an Android camera companion app for Olympus and OM SYSTEM cameras.

The goal is to make the main OI.Share workflows available with a more stable implementation and a modernized UI, while adding USB tethering support for camera control.

## Goals

- cover the existing OI.Share feature set with a cleaner Android UI
- improve stability around camera connection, remote capture, live view, and image transfer
- implement USB tethering for supported OM SYSTEM camera workflows
- keep Wi-Fi and USB/PTP camera communication easier to test and maintain

## What Is Implemented

- most of the core OI.Share-style workflows
- Wi-Fi camera connection, remote capture, live view, and image transfer
- geotagging and camera setting workflows
- USB tethering
- several USB tethering control features
- USB/PTP camera property parsing, formatting, and capability mapping
- deep-sky capture and live stacking workflow

## Compatibility

Android 14+ is supported. The app is currently validated on OM-1; other camera bodies are listed by expected protocol support and still need device-backed testing.

Legend:

- Tested: verified in db link
- Expected: the camera should expose the required Wi-Fi/OI.Share-style workflow, but needs db link validation
- Target: the camera is in the OM Capture tether-compatible family, but needs db link validation
- N/A: not applicable or not currently targeted for that body

| Camera body | Wi-Fi remote / live view | Library import | Source switching | USB tethering |
| --- | --- | --- | --- | --- |
| Olympus PEN E-PL6 | N/A | N/A | N/A | N/A |
| Olympus PEN E-P5 | Expected | Expected | N/A | N/A |
| Olympus OM-D E-M1 | Expected | Expected | N/A | Target, firmware 2.0+ |
| Olympus OM-D E-M10 | Expected | Expected | N/A | N/A |
| Olympus PEN E-PL7 | Expected | Expected | N/A | N/A |
| Olympus OM-D E-M5 Mark II | Expected | Expected | N/A | Target |
| Olympus OM-D E-M10 Mark II | Expected | Expected | N/A | N/A |
| Olympus PEN-F | Expected | Expected | N/A | N/A |
| Olympus OM-D E-M1 Mark II | Expected | Expected | Expected | Target |
| Olympus PEN E-PL8 | Expected | Expected | N/A | N/A |
| Olympus OM-D E-M10 Mark III | Expected | Expected | N/A | N/A |
| Olympus PEN E-PL9 | Expected | Expected | N/A | N/A |
| Olympus OM-D E-M1X | Expected | Expected | Expected | Target |
| Olympus OM-D E-M5 Mark III | Expected | Expected | N/A | N/A |
| Olympus PEN E-PL10 | Expected | Expected | N/A | N/A |
| Olympus OM-D E-M1 Mark III | Expected | Expected | Expected | Target |
| Olympus OM-D E-M10 Mark III S | Expected | Expected | N/A | N/A |
| Olympus OM-D E-M10 Mark IV | Expected | Expected | N/A | N/A |
| Olympus PEN E-P7 | Tested | Tested | Tested | N/A |
| OM SYSTEM OM-1 | Tested | Tested | Tested | Tested, partial controls |
| OM SYSTEM OM-5 | Expected | Expected | N/A | N/A |
| OM SYSTEM OM-1 Mark II | Expected | Expected | Expected | Target |
| OM SYSTEM E-M1 Mark III ASTRO | Expected | Expected | Expected | Target |
| OM SYSTEM OM-3 | Expected | Expected | N/A | N/A |
| OM SYSTEM OM-5 Mark II | Expected | Expected | N/A | N/A |

## Notes

- Android 14+ is supported
- development and testing are currently based on an OM-1 camera
- behavior on other camera bodies and firmware versions is not guaranteed
- camera compatibility depends on the protocol features exposed by the device

## Next Steps

1. fix remaining USB tethering control mappings that do not yet line up with the camera values
2. improve USB tethering stability and recovery behavior
3. broaden device-backed validation across more camera bodies and firmware versions
4. refine USB property coverage where descriptors differ by model

## support
https://buymeacoffee.com/modang

