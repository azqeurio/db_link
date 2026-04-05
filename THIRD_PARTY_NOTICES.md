# Third-Party Notices

This repository contains original project code under the MIT License in [LICENSE](C:/Users/godth/OneDrive/바탕%20화면/pl36/modern-app/LICENSE).

The Android app depends on third-party libraries and SDKs with their own terms. The list below reflects the direct dependencies declared in [app/build.gradle.kts](C:/Users/godth/OneDrive/바탕%20화면/pl36/modern-app/app/build.gradle.kts).

## Apache License 2.0

- AndroidX Core, SplashScreen, ExifInterface, Activity Compose, Lifecycle, Navigation Compose, DataStore, WorkManager, ProfileInstaller, CameraX
- AndroidX Compose Foundation, Animation, Material 3, Material Icons, Runtime Saveable, UI, UI Graphics, UI Tooling Preview
- OkHttp
- Google Material Components for Android
- Google Maps Compose

## Google SDK Terms

- Google Play Services Location
- Google Play Services Maps
- ML Kit Barcode Scanning

## Eclipse Public License 1.0

- JUnit 4.13.2

## Dual Licensed: LGPL 2.1 Or CDDL 1.0

- LibRaw 0.22.0 source code is vendored under `app/src/main/cpp/third_party/LibRaw-0.22.0`.
- Upstream project: [LibRaw](https://github.com/LibRaw/LibRaw)
- Upstream license files:
  - `app/src/main/cpp/third_party/LibRaw-0.22.0/LICENSE.LGPL`
  - `app/src/main/cpp/third_party/LibRaw-0.22.0/LICENSE.CDDL`

## Notes

- Additional transitive dependencies may apply through the Android and Google libraries listed above.
- Packaged third-party metadata may also appear in generated build outputs under `app/build`.
- Review upstream project documentation before distributing public binaries.
