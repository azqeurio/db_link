# Current App Summary

## Included now

- Home with quick actions for setup, remote control, photo import, and geotagging
- Camera setup screen with connection and permission staging
- Remote control screen with real repository-backed shutter and Live View start/stop actions
- Photo import screen with mobile/original presets and geotag import readiness
- Places screen with modern phone-driven geotagging:
  - one-tap location pins
  - session-based foreground tracking
  - camera clock offset control
  - import-by-time matching toggle
- Settings screen with persisted user preferences and optional advanced tools
- Debug-only command-list workbench in debug builds

## Hidden or removed from the product UI

- firmware update flows are not part of the current product navigation
- photo editing is intentionally not included

## Modernized architecture

- single-activity Compose shell
- capability-driven UI from parsed CGI support
- OkHttp network gateway
- DataStore-backed user settings persistence
- Fused Location geotagging workflow with bounded local history
- debug tooling kept out of the everyday user experience

## Still to finish

- real QR / BLE onboarding
- real Live View stream rendering
- real property write flows for exposure and other camera settings
- actual image download pipeline and import writes
- import-time EXIF geotag application using the captured place history
