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

Compatibility is currently validated against a Galaxy S25+ and an OM-1. Other devices may work when they expose the same camera protocol features, but they are not guaranteed yet.

### Device Matrix

| Device setup | Wi-Fi connection | Remote controls | Live view | Library import | Source switching | USB tethering | USB controls | Status |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Galaxy S25+ + OM-1 | Supported | Supported | Supported | Supported | Supported | Supported | Partial | Primary test setup |
| Other Android phones + OM-1 | Untested | Untested | Untested | Untested | Untested | Experimental | Experimental | Not guaranteed |
| Galaxy S25+ + other Olympus / OM SYSTEM cameras | Experimental | Experimental | Experimental | Experimental | Depends on camera support | Experimental | Experimental | Needs device validation |
| Other Android phones + other Olympus / OM SYSTEM cameras | Untested | Untested | Untested | Untested | Untested | Untested | Untested | Not guaranteed |

### Feature Status

| Feature | Current support | Notes |
| --- | --- | --- |
| Wi-Fi pairing and reconnect | Supported on OM-1 test setup | Reconnect behavior has been stabilized, but should be validated on more phones. |
| Remote capture over Wi-Fi | Supported on OM-1 test setup | Camera mode and drive mode availability depends on the camera command list. |
| Live view over Wi-Fi | Supported on OM-1 test setup | Stream stability may vary by phone Wi-Fi behavior. |
| Library browsing and transfer | Supported on OM-1 test setup | High Speed and Slow Mode are available for different camera server tolerance levels. |
| Card/source switching | Supported on OM-1 test setup | Requires camera support for play target slot commands. |
| USB tethering connection | Supported on OM-1 test setup | Stability is still being improved. |
| USB tethering controls | Partial | Some control mappings still need correction against camera values. |
| USB library/import | Experimental | Behavior can differ by camera storage descriptors. |
| Geotagging | Supported on phone-side location data | Requires location permission and device location services. |
| Deep-sky workflow | Experimental | Available in-app, but not broadly validated across devices. |

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

