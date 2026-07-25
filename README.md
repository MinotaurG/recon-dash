# Recon Dash

A CarPlay-inspired navigation companion for the **Royal Enfield Tripper Dash** (Guerrilla 450, Himalayan 450, and compatible round TFT displays). Recon Dash streams offline turn-by-turn navigation and a live map to the bike's cluster over Wi-Fi, and turns the phone into the setup/companion screen so the rider keeps their eyes on the road.

> Independent, community project. Not affiliated with, endorsed by, or associated with Royal Enfield. "Royal Enfield" and "Tripper" are trademarks of their respective owners.

## Features

- **Offline-first navigation** — on-device routing (Valhalla) and pre-cached vector map tiles (PMTiles); no data connection needed during a ride.
- **Dash projection** — streams a live navigation map to the bike's display over the K1G protocol (H.264 over Wi-Fi), or sends turn-by-turn only in analogue mode.
- **CarPlay-style UI** — dark, glanceable, single-purpose screens with a gold accent.
- **Search** — Photon (OSM) with an optional Google Places layer for local POIs.
- **Ride history, favorites, wallpapers, and a telemetry lab** for decoding the dash's instrument data.

## Tech

Kotlin · Jetpack Compose · Hilt · Room · Coroutines · MapLibre GL · Valhalla (on-device, JNI) · a C++/NDK geo hot path.

## Build

```bash
./gradlew :app:assembleDebug        # debug APK
./gradlew :app:testDebugUnitTest    # unit tests
```

Requires the Android SDK (set `sdk.dir` in `local.properties`) and, for the native geo layer, the NDK + CMake. The prebuilt Valhalla routing library is not committed — see [`valhalla/README.md`](valhalla/README.md) for how to fetch it.

`local.properties` (git-ignored) may hold an optional `GOOGLE_PLACES_KEY` to enable the Google Places search layer; without it the app falls back to Photon.

## Status

Under active development. Navigation reliability is the current focus. Hardware-validated on Guerrilla 450 and Himalayan 450 (firmware 11.63).

## License

[GNU AGPL-3.0](LICENSE).
