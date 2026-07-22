# :valhalla module

On-device routing via the Valhalla engine (C++), consumed through JNI.

## Prebuilt native library

`src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86_64}/libvalhalla-wrapper.so` are **prebuilt
binaries** (~371 MB total), not built from this repo. They export the JNI symbol
`Java_com_valhalla_valhalla_ValhallaKotlin_route` consumed by `ValhallaKotlin.kt`.

### The .so are NOT in git

They are gitignored (too large, and hard to rebuild). To get a build-ready checkout,
**download our backup from R2 and unzip into `jniLibs/`:**

```bash
cd valhalla/src/main/jniLibs
curl -L -o so.zip https://pub-10f8e863c0f544798593ccdb61ffd2a9.r2.dev/deps/valhalla-wrapper-so.zip
unzip so.zip && rm so.zip     # restores arm64-v8a/, armeabi-v7a/, x86_64/
```

(R2 bucket `recon-dash-data`, key `deps/valhalla-wrapper-so.zip`, sha256
`67f1fdef7b593396ffdd59596dd1e4af60285f30474e51ffa819eecb57eb6690`.)

### Provenance

- Original source: the **Rallista / valhalla-mobile** prebuilt AAR (`valhallaMobile` version
  pinned in `gradle/libs.versions.toml`). The `.so` were extracted from that AAR; this module
  wraps them with its own public `ValhallaKotlin` binding + config, because the AAR's own
  binding is `internal` and its public API references unpublished model classes. The R2 backup
  above exists so the build survives the upstream AAR disappearing.
- Building from source is hard: it requires cross-compiling Valhalla (C++) plus Boost,
  Protobuf, zlib and geometry deps for all three Android ABIs with the NDK. Valhalla's CMake
  is not Android-friendly out of the box — this is why a prebuilt is used.

### Config

`src/main/assets/valhalla_config_template.json` is the Valhalla 3.6.3 config (from the
valhalla-mobile test assets). `ValhallaConfig.kt` substitutes `__TILE_EXTRACT__` with the
on-device `valhalla_tiles.tar` path. Routing tiles are downloaded per-region at runtime
(see `data/RegionManager.kt`), not bundled.
