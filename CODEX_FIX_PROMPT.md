# Codex Fix Prompt — PL36 Camera Link Android App

## Context

This is an Android app (Kotlin + Jetpack Compose) for tethered camera control targeting Olympus/OM System cameras. It supports WiFi HTTP and USB/PTP transport, live view, remote shooting, image transfer, GPS geotagging, and deep-sky astrophotography live stacking.

A full code audit has been completed. Some issues were already fixed. The remaining unfixed issues are described below with exact locations, what's wrong, and the required fix.

---

## Issue 1 — CRITICAL: `_uiState` non-atomic read-modify-write (201 occurrences)

### File
`app/src/main/java/dev/pl36/cameralink/ui/MainViewModel.kt`

### Problem
There are **201 instances** of `_uiState.value = _uiState.value.copy(...)`. This is a non-atomic read-modify-write. When multiple coroutines on the Main dispatcher suspend and resume, one coroutine's state update can silently overwrite another's changes, causing lost UI state updates.

There is already a helper at line ~287:
```kotlin
private inline fun updateUiState(transform: (MainUiState) -> MainUiState) {
    _uiState.update(transform)
}
```

The `_uiState.update {}` function from `kotlinx.coroutines.flow` is atomic — it retries if the state was concurrently modified.

### Required Fix
Convert ALL 201 occurrences of `_uiState.value = _uiState.value.copy(...)` to use either `updateUiState { it.copy(...) }` or `_uiState.update { it.copy(...) }`.

**Pattern A — Simple field update:**
```kotlin
// BEFORE:
_uiState.value = _uiState.value.copy(wifiState = effectiveWifiState)

// AFTER:
updateUiState { it.copy(wifiState = effectiveWifiState) }
```

**Pattern B — Nested field update referencing current state:**
```kotlin
// BEFORE:
_uiState.value = _uiState.value.copy(
    remoteRuntime = _uiState.value.remoteRuntime.copy(statusLabel = "Ready"),
)

// AFTER:
updateUiState { current ->
    current.copy(
        remoteRuntime = current.remoteRuntime.copy(statusLabel = "Ready"),
    )
}
```

**Pattern C — Full state replacement (line ~1615):**
```kotlin
// BEFORE:
_uiState.value = nextState

// AFTER:
_uiState.update { nextState }
```

**IMPORTANT**: When the lambda references `_uiState.value` internally (to read nested state like `remoteRuntime` or `transferState`), replace those with the lambda parameter (e.g., `current` or `it`). Otherwise the read is still non-atomic.

### Verification
After fix, `grep -c '_uiState\.value\s*=' MainViewModel.kt` should return **0** (or only the initial assignment in the constructor).

---

## Issue 2 — HIGH: `usbAutoImportJob` not cancelled in `onCleared()`

### File
`app/src/main/java/dev/pl36/cameralink/ui/MainViewModel.kt`

### Problem
In `onCleared()` (around line 492-510), many jobs are explicitly cancelled but `usbAutoImportJob` and `usbPropertySyncJob` are missing. If the ViewModel is cleared while a USB auto-import drain loop is running, it will keep running until it finishes or errors.

### Required Fix
Add these two lines to `onCleared()`, before `omCaptureUsbManager.close()`:
```kotlin
usbAutoImportJob?.cancel()
usbPropertySyncJob?.cancel()
```

---

## Issue 3 — HIGH: `ImageDownloadService` companion object static state

### File
`app/src/main/java/dev/pl36/cameralink/core/download/ImageDownloadService.kt`

### Problem
Lines 67-70 use companion object mutable statics:
```kotlin
private var pendingImages: List<CameraImage> = emptyList()
private var pendingSaveLocation: String = ""
private var pendingPlayTargetSlot: Int? = null
```

These are set in `startDownload()` and read in `onStartCommand()` as a fallback. Since the service uses `START_REDELIVER_INTENT` and serializes data to Intent extras, the statics are redundant. They create confusion and a false sense of state persistence across process death.

### Required Fix
1. Remove the three `pendingImages`, `pendingSaveLocation`, `pendingPlayTargetSlot` companion statics.
2. In `startDownload()`, remove the lines that set them.
3. In `onStartCommand()`, change the fallback:
```kotlin
// BEFORE:
val images = deserializeImages(intent?.getStringExtra(EXTRA_IMAGES_JSON)).ifEmpty { pendingImages }
// AFTER:
val images = deserializeImages(intent?.getStringExtra(EXTRA_IMAGES_JSON))
if (images.isEmpty()) {
    D.transfer("No images to download — stopping service")
    stopSelf()
    return START_NOT_STICKY
}
```
4. Similarly, remove the fallbacks for `saveLocation` and `playTargetSlot` that reference the statics.
5. Keep `_progress` as a static StateFlow — that's fine for observability.

---

## Issue 4 — MEDIUM: `Thread.sleep()` in PtpSession (non-suspend blocking)

### File
`app/src/main/java/dev/pl36/cameralink/core/usb/PtpSession.kt`

### Problem
Three `Thread.sleep()` calls exist:
- Line 533: `Thread.sleep(100L)`
- Line 546: `Thread.sleep(100L)`
- Line 758: `Thread.sleep(1)`

PtpSession methods are NOT suspend functions. They are called from `Dispatchers.IO` in `OmCaptureUsbManager`. While this is low-severity (IO pool has 64 threads, blocking is brief), the correct approach for coroutine-compatible code is to make these methods `suspend` and use `delay()`.

### Required Fix
**Option A (minimal, preferred):** Leave `Thread.sleep` as-is. PtpSession is a blocking transport layer and converting to suspend would require restructuring the entire USB stack. Add a comment:
```kotlin
// Blocking sleep intentional: PtpSession methods are called from Dispatchers.IO
// and are not suspend functions. The 100ms delay is required for Olympus PC mode
// event polling and is brief enough not to starve the IO pool.
Thread.sleep(100L)
```

**Option B (if you want to convert):** Make `initializeOlympusPcMode()` a `suspend` function, replace `Thread.sleep(100L)` with `delay(100)`, and update all callers in `OmCaptureUsbManager` to call it from a suspending context (they already do via `withContext(Dispatchers.IO)`).

---

## Issue 5 — MEDIUM: No crash analytics

### Problem
No Crashlytics, Sentry, or any remote crash reporting. The app has a local `FileLogger` that captures crashes to disk, but these are invisible to the developer unless the user manually exports logs.

### Required Fix
Add Firebase Crashlytics:

1. In root `build.gradle.kts`:
```kotlin
plugins {
    // ... existing
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.crashlytics") version "3.0.3" apply false
}
```

2. In `app/build.gradle.kts`:
```kotlin
plugins {
    // ... existing
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

dependencies {
    // ... existing
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-crashlytics")
}
```

3. Add `google-services.json` from Firebase Console to `app/` directory.

4. In `CameraLinkApplication.onCreate()`, Crashlytics initializes automatically.

---

## Issue 6 — LOW: `pendingUsbAutoImportHandles` not cleared on USB disconnect

### File
`app/src/main/java/dev/pl36/cameralink/ui/MainViewModel.kt`

### Problem
When USB disconnects (runtime state observer fires with `operationState == Error` and `summary == null`), `pendingUsbAutoImportHandles` is cleared at line ~354 via `clearUsbAutoImportTracking()`. This appears to already work for the error/disconnect case.

However, `clearUsbAutoImportTracking()` should also be called when `usbAutoImportJob` is cancelled in `onCleared()`.

### Required Fix
In `onCleared()`, after cancelling `usbAutoImportJob`, add:
```kotlin
pendingUsbAutoImportHandles.clear()
completedUsbAutoImportHandles.clear()
```

---

## Summary of Fixes by Priority

| Priority | Issue | Action |
|----------|-------|--------|
| CRITICAL | `_uiState.value =` race (201 occurrences) | Convert all to `updateUiState { }` or `_uiState.update { }` |
| HIGH | `usbAutoImportJob` not in `onCleared()` | Add cancel + cleanup |
| HIGH | `ImageDownloadService` static fallbacks | Remove static state, rely on Intent |
| MEDIUM | `Thread.sleep` in PtpSession | Add comments (or convert to suspend) |
| MEDIUM | No crash analytics | Add Firebase Crashlytics |
| LOW | USB handle cleanup in `onCleared()` | Clear tracking sets |

---

## Rules for Codex

- Do NOT restructure/split MainViewModel — that is a separate task
- Do NOT add a DI framework — that is a separate task
- Do NOT change any business logic or feature behavior
- Do NOT modify tests unless a fix breaks compilation
- Every `_uiState.value = _uiState.value.copy(...)` must become `updateUiState { current -> current.copy(...) }` or `_uiState.update { current -> current.copy(...) }`
- When converting, ensure ALL references to `_uiState.value.someField` inside the copy block become `current.someField`
- Verify the project still compiles after changes
