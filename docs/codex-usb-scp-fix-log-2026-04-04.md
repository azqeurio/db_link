# Codex USB/SCP Fix Log - 2026-04-04

## Goal

Apply OM System USB tether fixes against the existing Android app without inventing new protocol behavior. Base changes on:

- captured PTP logs from `C:/Users/godth/Downloads`
- the current app code in `modern-app`
- strings/resources from the installed OM Capture desktop app in `OM Capture/i18n`

## Logs and sources reviewed

- `camera_link_session (10).log`
- `camera_link_session (4).log`
- `camera_link_session (3).log`
- `camera_link_session (6).log`
- `camera_link_session (7).log`
- `camera_link_session_prev (3).log`
- `OM Capture/i18n/ENU/app.loc`
- `OM Capture/i18n/ENU/prop.loc`

## What the logs showed

### 1. Mode-dial handling was not just a missing whitelist entry

From `camera_link_session (10).log` around lines 2373-2433:

- `0xc108 param0=0xd062 param1=0x2`
- `0xc108 param0=0xd084 param1=0x7`
- `0xc108 param0=0xd061 param1=0x2`
- the app then started a property refresh batch
- `0xc105` arrived immediately after
- the USB/PTP session then failed

This means the breakage path was:

1. mode dial change starts
2. app refreshes properties during the transition
3. camera sends `0xC105 CameraControlOff`
4. transport/session falls over

### 2. `0xD084` should not be treated as a general mode-dial UI property

The codebase already uses `0xD084` as `LIVE_VIEW_REC_VIEW_STATE_PROP`.
The observed event pattern also matched a rec-view/live-view state transition, not a stable exposure-mode property.

### 3. SCP value lists must prefer live descriptors

From `camera_link_session (4).log`:

- `D00C` descriptor payload size indicates 5 enum entries
- `D065` indicates 2 enum entries
- `D0C4` indicates 2 enum entries
- `D0C5` indicates 5 enum entries
- `D100` indicates 2 enum entries
- `D101` indicates 3 enum entries
- `D11A` indicates 4 enum entries
- `D11B` indicates 4 enum entries
- `D11C` indicates 2 enum entries

That confirmed several prior synthetic fallback lists were too broad or unsupported.

### 4. OM Capture strings were useful for human-readable labels

Verified against OM Capture resources:

- Face / Eye labels
- Face Priority AF labels
- Subject Detection labels
- High Res Shot labels
- S-IS labels
- Color Space labels
- Picture Mode labels

## Code changes made

### USB mode-dial recovery

Files:

- `app/src/main/java/dev/pl36/cameralink/core/usb/OmCaptureUsbCameraEvent.kt`
- `app/src/main/java/dev/pl36/cameralink/core/usb/OmCaptureUsbManager.kt`
- `app/src/main/java/dev/pl36/cameralink/ui/MainViewModel.kt`

Changes:

- added `SessionResetRequired` camera event
- treated `0xD062` as the body mode-dial transition property
- stopped treating `0xD084` as a mode-dial refresh trigger
- handled `0xC105 CameraControlOff` explicitly
- when `CameraControlOff` is seen, stop live view, close the active session, and emit a reset event
- suppressed immediate live-view property refreshes during the mode-dial settle window
- when the reset event reaches the view model, clear pending refresh work and re-inspect the USB camera after a short delay to rebind the tether session

Reason:

- this matches the observed camera behavior more closely than forcing a property refresh into a session the camera is actively tearing down

### SCP gesture and layout

File:

- `app/src/main/java/dev/pl36/cameralink/feature/remote/RemoteScreen.kt`

Changes:

- moved SCP open/close gesture ownership to the live-view container
- kept the SCP overlay itself render-only plus tap-to-dismiss
- reset SCP/open extra-property state when USB live-view quick access is disabled
- added a new adaptive SCP overlay used by the screen
- portrait layout now renders as 3 columns
- quarter-turn landscape layout now renders as 5 columns
- landscape cells use a more compact layout and the overlay content can scroll vertically

Reason:

- the old implementation split gesture responsibility between the live-view layer and the overlay layer, which was fragile across re-entry
- the new path keeps drag/tap interpretation in one place and only uses the SCP overlay for presentation

### SCP value formatting cleanup

File:

- `app/src/main/java/dev/pl36/cameralink/core/usb/OlympusUsbValueFormatter.kt`

Changes:

- kept descriptor-first enumeration behavior
- aligned Face / Eye labels with OM Capture strings
- removed the unsupported "Auto Eye" label guess for `0xD0C5`
- updated image stabilizer labels to OM Capture ordering:
  - `S-IS Off`
  - `S-IS 1`
  - `S-IS 2`
  - `S-IS 3`
  - `S-IS Auto`
- kept existing High Res, Drive, Subject Detection, Face Priority, and Color Space label fixes

Notes:

- the exact raw-value order for every `D11B` mode is still an inference from OM Capture labels plus observed sequential raw values; the descriptor size was confirmed from logs, but the raw descriptor bytes were not captured in readable form

### Follow-up fixes from `camera_link_session (11).log`

Files:

- `app/src/main/java/dev/pl36/cameralink/feature/remote/RemoteScreen.kt`
- `app/src/main/java/dev/pl36/cameralink/ui/MainViewModel.kt`
- `app/src/main/java/dev/pl36/cameralink/core/usb/OmCaptureUsbManager.kt`
- `app/src/main/java/dev/pl36/cameralink/core/usb/OlympusUsbValueFormatter.kt`

Changes:

- changed SCP open/close drag from vertical to left-to-right, with the drag axis rotating along with `readableRotation`
- portrait SCP now uses 4 columns, landscape SCP uses 6 columns
- landscape SCP now fills the live-view area instead of staying as a padded rotated card
- shifted the landscape remote deck downward and added more spacing so the upper controls are not jammed against the top edge
- SCP now refreshes the expanded USB property snapshot every time it opens instead of only when the extra-property list is empty
- ISO now follows the active USB property code from the current snapshot instead of always forcing `0xD007`
- added an OM-1 ISO fallback path for `0xD005` in the USB manager because the new log repeatedly emits `0xD005` while `0xD007` never appears
- added sync/write plumbing for drive-related numeric USB properties:
  - `0xD0CC` interval count
  - `0xD0CD` interval seconds
  - `0xD0EC` timer delay
- opening the existing Drive Settings picker in USB mode now triggers an extra-property refresh so those values populate before editing
- pruned the SCP extra-property allowlist down to the known OM Capture-facing controls so the stray `0x...` tiles stop surfacing
- added `35073 -> Monotone` to Picture Mode formatting
- allowed High Res Shot to surface `0 -> Off` even when the camera descriptor only reports `1,2`
- added synthetic fallback value lists for subject type, stabilizer, and color space so the picker can still render choices when OM descriptors are sparse
- only show `Subject Type` in SCP when subject detection is actually on

Evidence used:

- `camera_link_session (11).log` confirms `0xD00C` Picture Mode writes succeed and reports enum values `33024,34817,34818,34819,35073`
- `camera_link_session (11).log` confirms `0xD065` High Res Shot writes succeed and currently reports enum values `1,2`
- `camera_link_session (11).log` shows repeated stale-event drains for `0xD005`, which strongly suggests the OM-1 ISO prop is not the legacy `0xD007` on this body/session
- OM Capture `app.loc` / `prop.loc` strings were used for Picture Mode, High Res Shot, Subject Detection, S-IS, and Color Space labels where they matched the exposed control names

Notes:

- `0xD005` as the OM-1 ISO fallback is still an evidence-based inference from the attached logs, not a directly labelled descriptor dump
- I left Subject Type values aligned to OM Capture resources (`Human`, `Motorsports`, `Airplanes`, `Trains`, `Birds`) rather than inventing a different split without protocol evidence

## Verification

Build command run:

```powershell
.\gradlew.bat :app:compileDebugKotlin
```

Result:

- build passed on 2026-04-04

## Remaining device checks

The following still need real camera validation:

- body mode dial now reconnects and refreshes the app state correctly
- SCP drag behavior after leaving and re-entering live view
- quarter-turn landscape SCP ergonomics on device
- final `D11B` stabilizer raw-to-label ordering on OM-1

## Follow-up fixes for live view persistence

Files:

- `app/src/main/java/dev/pl36/cameralink/ui/MainViewModel.kt`
- `app/src/main/java/dev/pl36/cameralink/core/usb/OmCaptureUsbManager.kt`

Changes:

- stopped treating USB live view as only the manager's current state and added a `usbLiveViewDesired` intent flag in `MainViewModel`
- explicit user stop (`toggleUsbLiveView` off) now clears that desired flag and cancels any pending recovery job
- every USB live-view start path now restores the desired flag, cancels stale recovery work, and re-attaches the frame collector
- runtime-state drops now coalesce into a single recovery job that waits for the USB manager to go idle, then either:
  - restarts live view on the current session, or
  - re-runs USB inspect first when the session has fallen into an error/null-summary state
- re-entering USB surfaces with a desired live view but no cached session now triggers a fresh USB inspect instead of waiting for the user to toggle again
- `inspectConnectedCamera()` no longer hard-stops USB live view; it now uses the existing transport pause/resume path so library loads and session refreshes do not kill the stream when the session is still valid

Why:

- the app previously lost live view whenever an inspect path ran, because inspect forcibly called `stopUsbLiveView()`
- the UI also had no memory of whether the user wanted live view kept on, so any transient stop looked the same as an intentional stop
- this change keeps live view sticky unless the user explicitly toggles it off

## Follow-up fixes for SCP re-entry and High Res selection

Files:

- `app/src/main/java/dev/pl36/cameralink/feature/remote/RemoteScreen.kt`

Evidence used:

- `camera_link_session_prev.log` shows repeated touch-focus writes on `0xD051` and labels them as `AF Target`
- `camera_link_session_prev.log` separately shows explicit High Res writes on `0xD065` with values `0`, `1`, and `2`
- OM Capture ENU strings include:
  - `AF Target Mode`
  - `High Res Shot`
  - `High Res Shot Tripod`
  - `High Res Shot Handheld`

Changes:

- stopped preserving SCP open-state and active extra-property state with `rememberSaveable`; these are now ephemeral per live-view screen instance so they do not come back stale after leaving/re-entering the page
- force-close SCP and clear the active extra-property picker when:
  - the mode-picker surface changes
  - USB live view goes inactive
  - SCP quick access becomes unavailable
- opening SCP now clears any stale extra-property picker before refreshing the expanded USB property snapshot
- if the currently selected extra USB property is no longer present after a refresh/reconnect, it is cleared instead of leaving an old dial bound to stale state
- added explicit cell keys in the adaptive SCP grid so Compose keeps cell identity tied to `propCode` rather than position when the property set changes
- when opening an extra SCP property, the displayed label is now re-derived from the current USB property snapshot instead of trusting saved UI text alone

Reason:

- the re-entry bug matched stale Compose UI state more than protocol failure
- the log evidence keeps `0xD051` and `0xD065` clearly separated, so the safest fix was to remove stale SCP selection state that could make the UI reopen an older property while the user expected High Res

## QA recorder build

Timestamp:

- 2026-04-04 22:07:54 -04:00

Goal:

- create a separate installable QA build instead of modifying the normal app flow
- let the tester start a recording session, move through app UIs, change values, manipulate the camera body directly, annotate what the camera actually showed, then finish the session with a generated report

Files:

- `app/build.gradle.kts`
- `app/src/main/java/dev/pl36/cameralink/CameraLinkApp.kt`
- `app/src/main/java/dev/pl36/cameralink/ui/MainViewModel.kt`
- `app/src/main/java/dev/pl36/cameralink/ui/QaRecorderUiState.kt`
- `app/src/main/java/dev/pl36/cameralink/feature/dashboard/DashboardScreen.kt`
- `app/src/main/java/dev/pl36/cameralink/feature/qa/QaRecorderScreen.kt`

Implementation:

- added a dedicated `qa` build type with:
  - application id `dev.pl36.cameralink.qa`
  - version suffix `-qa`
  - QA-only build flag `BuildConfig.QA_RECORDER_BUILD`
- added QA session state to `MainUiState`
- added a QA recorder screen reachable from the dashboard only in the QA build
- added session controls:
  - `Start`
  - `Finish`
  - `Clear`
- added quick jump buttons from the QA screen to:
  - Dashboard
  - Remote
  - OM Capture
  - Transfer
  - Settings
- added automatic timeline recording for:
  - screen navigation
  - live-view toggles
  - capture actions
  - USB property writes
  - drive/timer changes
  - EV/property changes
  - touch focus
  - OM Capture section selection
  - USB camera events (`PropertyChanged`, `ObjectAdded`, `SessionResetRequired`)
  - refreshed camera-property snapshot changes, so body-side mode dial or setting changes are recorded after the app rereads the camera state
- each recorded item can store:
  - tester-entered `Camera matched value`
  - tester-entered `Memo`
- finishing a QA session writes a plain-text report under the app external files `qa-reports` directory

Mode dial handling:

- physical body mode changes are captured twice when possible:
  - raw USB event entry (`0xD062` / reset event path)
  - human-readable camera-state change entry after property refresh (`Exposure Mode changed ...`)

Verification:

```powershell
.\gradlew.bat :app:compileQaKotlin
.\gradlew.bat :app:assembleQa
```

Artifact:

- `C:\Users\godth\AppData\Local\Temp\cameralink-build\PL36 Camera Link\app\outputs\apk\qa\app-qa.apk`

## Remote-integrated recorder

Timestamp:

- 2026-04-04 22:44:00 -04:00

Goal:

- remove the dedicated QA-only route/screen flow
- make short test recording work directly from the existing Remote UI
- arm a 20-second camera-event capture window right after a real Remote control action
- let the tester stop the window manually from the Remote screen top area, or let it end automatically after 20 seconds

Files:

- `app/build.gradle.kts`
- `app/src/main/java/dev/pl36/cameralink/CameraLinkApp.kt`
- `app/src/main/java/dev/pl36/cameralink/feature/remote/RemoteScreen.kt`
- `app/src/main/java/dev/pl36/cameralink/ui/MainViewModel.kt`
- `app/src/main/java/dev/pl36/cameralink/ui/QaRecorderUiState.kt`

Implementation:

- removed the separate `qa` build type and the QA-only navigation entry point
- deleted the standalone QA recorder screen so recording is no longer a separate workflow
- kept the recorder/report backend, but changed it to a short-lived Remote-triggered session model
- added `armQaRecorderFromRemote()` in `MainViewModel`:
  - starts a fresh session on the first qualifying Remote action
  - extends/re-arms the same session to 20 seconds if another qualifying Remote action happens before timeout
  - captures the initial USB camera snapshot when the window opens
- added manual stop support through `finishQaRecorderSession()` and an auto-stop job that closes the session after 20 seconds
- kept camera-side USB property/event logging active during the window so body-side mode dial or setting changes are still written into the report
- added a Remote top overlay banner with:
  - active trigger label
  - remaining seconds
  - `Finish` button
- wired the existing Remote controls so these actions arm the recorder before dispatch:
  - live-view toggle
  - capture
  - EV change
  - interval start/stop/count/seconds
  - drive/timer/mode changes
  - property picker value commits
  - touch AF
  - SCP USB property writes
  - manual focus drive

Notes:

- the old QA dashboard card path is no longer used; testing now happens inside the normal Remote screen only
- reports still write under the app external files `qa-reports` directory and now include the trigger label plus finish reason
