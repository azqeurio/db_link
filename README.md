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
- No target: the feature is not currently targeted for that body
- N/A: not applicable, usually because the body has a single card slot

| Camera body | Release year | Wi-Fi remote / live view | Library import | Source switching | USB tethering | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| Olympus PEN E-PL6 | 2013 | No target | No target | N/A | No target | No built-in Wi-Fi workflow targeted by db link |
| Olympus PEN E-P5 | 2013 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |
| Olympus OM-D E-M1 | 2013 | Expected | Expected | N/A | Target, firmware 2.0+ | Official tether-compatible family |
| Olympus OM-D E-M10 | 2014 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |
| Olympus PEN E-PL7 | 2014 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |
| Olympus OM-D E-M5 Mark II | 2015 | Expected | Expected | N/A | Target | Official tether-compatible family |
| Olympus OM-D E-M10 Mark II | 2015 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |
| Olympus PEN-F | 2016 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |
| Olympus OM-D E-M1 Mark II | 2016 | Expected | Expected | Expected | Target | Dual-card source switching needs validation |
| Olympus PEN E-PL8 | 2016 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |
| Olympus OM-D E-M10 Mark III | 2017 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |
| Olympus PEN E-PL9 | 2018 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |
| Olympus OM-D E-M1X | 2019 | Expected | Expected | Expected | Target | Dual-card source switching needs validation |
| Olympus OM-D E-M5 Mark III | 2019 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |
| Olympus PEN E-PL10 | 2019 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |
| Olympus OM-D E-M1 Mark III | 2020 | Expected | Expected | Expected | Target | Dual-card source switching needs validation |
| Olympus OM-D E-M10 Mark III S | 2020 | Expected | Expected | N/A | No target | Regional E-M10 Mark III variant |
| Olympus OM-D E-M10 Mark IV | 2020 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |
| Olympus PEN E-P7 | 2021 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |
| OM SYSTEM OM-1 | 2022 | Tested | Tested | Tested | Tested, partial controls | Primary validated camera body |
| OM SYSTEM OM-5 | 2022 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |
| OM SYSTEM OM-1 Mark II | 2024 | Expected | Expected | Expected | Target | Dual-card source switching needs validation |
| OM SYSTEM E-M1 Mark III ASTRO | 2024 | Expected | Expected | Expected | Target | Same app target as E-M1 Mark III |
| OM SYSTEM OM-3 | 2025 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |
| OM SYSTEM OM-5 Mark II | 2025 | Expected | Expected | N/A | No target | Wi-Fi workflow needs validation |

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

