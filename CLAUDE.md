# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Run unit tests
./gradlew :app:testDebugUnitTest

# Compile check (fast, no APK)
./gradlew :app:compileDebugKotlin
```

Requires `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools` (or set `sdk.dir` in `local.properties`).

## Project Overview

Recon Dash — motorcycle navigation companion for Royal Enfield Tripper Dash (and compatible displays). CarPlay-inspired UI. Targets Guerrilla 450 primarily, works with Himalayan 450.

## Tech Stack

- **Language:** Kotlin, coroutines
- **UI:** Jetpack Compose + Material 3, dark-first (gold accent)
- **DI:** Hilt
- **Maps (phone):** MapLibre GL (vector tiles, offline via PMTiles) — NOT YET IMPLEMENTED
- **Maps (dash stream):** Custom Canvas renderer → H.264 (in `dash/map/`)
- **Routing:** GraphHopper on-device (planned) / OSRM fallback (in `dash/nav/Router.kt`)
- **Search:** Photon API autocomplete — NOT YET IMPLEMENTED
- **DB:** Room
- **Dash protocol:** K1G packets over UDP, WiFi network binding, H.264/RTP streaming

## Architecture

```
com.recon.dash/
├── dash/              ← PROVEN protocol code (do NOT refactor without hardware testing)
│   ├── protocol/      K1G packet format, DashCommands
│   ├── video/         H.264 MediaCodec encoder, RTP packetizer, NAL processor
│   ├── map/           Canvas tile renderer (526×300), tile cache, Mercator
│   ├── nav/           OSRM router, nav engine, voice manager, polyline codec
│   ├── DashSession    Connection lifecycle (auth → stream)
│   ├── DashWifiManager  WiFi discovery + Android network binding
│   ├── DashConfig     Encrypted prefs for SSID/password
│   └── DashKeepAliveService  Foreground service (screen-off streaming)
├── ui/                Compose UI (CarPlay-style, single-screen nav) — TO BUILD
├── map/               MapLibre vector tiles integration — TO BUILD
├── routing/           On-device GraphHopper routing — TO BUILD
├── search/            Photon autocomplete — TO BUILD
├── data/              Room DB, wallpaper models — TO BUILD
└── util/              DebugLog
```

## Dash Protocol (the proven core)

The `dash/` package streams navigation to the bike's TFT display:
- Bike broadcasts WiFi (`RE_` prefix, password `12345678`)
- App connects, authenticates via RSA handshake (DashAuth)
- Sends route card packets at 1 Hz (turn-by-turn data for analogue mode)
- Streams H.264 video at 4 fps to UDP :5000 (digital/projection mode)
- Maintains projection heartbeat at 4 Hz
- Handles joystick button events from the dash

Protocol validated on: Guerrilla 450, Himalayan 450 (firmware 11.63).

## Design Goals

- **Offline-first:** No network required during rides (pre-cached vector tiles + on-device routing)
- **Battery efficient:** Screen OFF streaming, adaptive frame rate (4fps moving, 1fps stopped), no tile fetching during ride
- **CarPlay UX:** Single-screen map, floating search, one-tap navigation start
- **Phone complements dash:** Phone is the setup/companion screen; dash shows the riding view
- **Analogue fallback:** Can send turn-by-turn only (no video) for minimal battery mode

## Hard Constraints

- `dash/` package: do not refactor without testing on physical bike
- Dash resolution: 526×300 pixels (round TFT, corners clipped by bezel)
- Dash WiFi has no internet — tiles MUST be pre-cached before connecting
- Frame rate: 4 fps max (dash decoder limitation), 2 fps when idle
- The round dash display clips content ~15px from edges

## Phase 2 (Future)

- OBD-II/CAN bus integration (diagnostic scanner) for live RPM, gear, fuel, engine temp
- iOS version (Swift/SwiftUI + VideoToolbox)
