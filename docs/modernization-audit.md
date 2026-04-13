# Architecture Notes

## Product Direction

The app targets a compact, explicit camera-companion architecture that keeps protocol compatibility while using modern Android patterns.

## Design goals

- reduce global mutable state
- make camera communication easier to test
- modernize storage, Bluetooth, and permission handling
- keep background work bounded and predictable
- present camera features through a simpler adaptive UI

## Implementation direction

### App shell

- single-activity Compose shell
- adaptive navigation for compact and expanded widths
- shared visual system and edge-to-edge layout

### State management

- repository-driven state
- isolated protocol and domain models
- immutable UI snapshots where practical

### Networking

- dedicated protocol gateway
- endpoint catalog, parsers, and capability negotiation
- explicit timeout and cancellation strategy

### Permissions

- scoped storage and `MediaStore`
- staged permission requests tied to feature entry points
- modern Bluetooth and nearby-device permissions

### Storage

- `MediaStore`-based import targets
- media-specific permission handling
- photo picker friendly paths where available

### Background work

- foreground service only for active long-running work
- WorkManager for resumable constrained tasks

### Persistence

- DataStore for app settings
- Room for structured local data as features expand

### UI/UX

- Compose-based controls and adaptive layout
- development tools kept separate from primary user flows
- clearer entry points for setup, remote control, transfer, and location features

## Recommended Implementation Order

1. command-list parsing and capability negotiation
2. connection session state and health checks
3. image library browsing and save pipeline
4. remote control commands and live-preview orchestration
5. firmware update state handling
6. device-specific adapters where protocol coverage needs expansion
