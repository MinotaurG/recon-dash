# CLAUDE.md
This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest

## Project Overview
Recon Dash — motorcycle navigation companion (Android). CarPlay-inspired UI for Royal Enfield Tripper Dash.
Kotlin + Compose + Hilt. Offline-first (vector maps, on-device routing).
The `dash/` package contains the proven Tripper protocol code (K1G packets, H.264 encoding, RTP, WiFi management) — do not refactor without hardware testing.
