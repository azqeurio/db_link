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

## Notes

- development and testing are currently based on a Galaxy S25+ and an OM-1 camera
- behavior on other phones, Android versions, camera bodies, and firmware versions is not guaranteed
- camera compatibility depends on the protocol features exposed by the device

## Next Steps

1. fix remaining USB tethering control mappings that do not yet line up with the camera values
2. improve USB tethering stability and recovery behavior
3. broaden device-backed validation across more camera bodies and Android devices
4. refine USB property coverage where descriptors differ by model

## support
https://buymeacoffee.com/modang

