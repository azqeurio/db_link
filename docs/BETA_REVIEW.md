# Beta Review

Reviewed on 2026-04-04 for GitHub beta packaging.

## Findings

1. Blocker for public repo packaging: `modern-app/index.html` was 31,248,407,427 bytes and is not part of the Android build. This would break or severely hinder a GitHub push, so it was intentionally excluded from `om`.
2. High risk for public repo packaging: `key1` at the workspace root behaves like a password-protected certificate or keystore container. It was intentionally excluded from `om` and should stay private.
3. Medium risk for public repo packaging: `modern-app/local.properties` contains a machine-specific Android SDK path and was intentionally excluded from `om`.
4. Medium runtime/privacy risk: release builds still initialize file logging on startup and keep category logging enabled by default in `modern-app/app/src/main/java/dev/pl36/cameralink/CameraLinkApplication.kt:11`, `modern-app/app/src/main/java/dev/pl36/cameralink/core/logging/D.kt:21`, and `modern-app/app/src/main/java/dev/pl36/cameralink/core/logging/FileLogger.kt:35`. That is acceptable for a beta if you want diagnostics, but it means release users can generate exported log files with device and camera session details.
5. Low risk / cleanup: `app/src/main/cpp/third_party/libraw-0.22.0.zip` is not used by `modern-app/app/src/main/cpp/CMakeLists.txt:6`, which builds from the unpacked `LibRaw-0.22.0` directory. The unused zip was excluded from `om`.

## Validation

- `C:\dev\pl36-modern-app\gradlew.bat testDebugUnitTest`
- `C:\dev\pl36-modern-app\gradlew.bat assembleRelease lintRelease`

Both commands completed successfully on 2026-04-04.

## om Contents

- Clean Android source project for GitHub upload
- Gradle wrapper and build scripts
- App source, native source, tests, and essential docs
- `release-assets/app-release.apk` and `release-assets/output-metadata.json` for manual GitHub Release upload

`release-assets/` is ignored by `.gitignore` so the APK does not get committed by accident.
