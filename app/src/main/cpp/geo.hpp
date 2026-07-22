// Pure C++ geo hot path — NO JNI types here, so it stays unit-testable off-device.
//
// Mirrors the Kotlin references it replaces:
//   - PolylineCodec.decode(encoded, precision)  (dash/nav/PolylineCodec.kt)
//   - Mercator.lngToTileX / latToTileY          (dash/map/Mercator.kt)
// The renderer works in PIXELS = fractional tile * TILE_SIZE (MapRenderer.kt:110-111),
// so project_point() returns pixels to match that convention exactly.
#pragma once

#include <string>
#include <vector>

namespace geo {

constexpr int TILE_SIZE = 256;

struct LatLng {
    double lat;
    double lng;
};

struct Pixel {
    double x;
    double y;
};

// Decode a Google/OSRM/Valhalla encoded polyline. `precision` is 5 for OSRM/Google,
// 6 for Valhalla — it MUST be passed in, never hardcoded (recon-dash uses both).
std::vector<LatLng> decode_polyline(const std::string& encoded, int precision);

// Web Mercator (slippy-map) projection to absolute pixel coordinates at `zoom`.
// pixels = fractional_tile * TILE_SIZE, matching Mercator.kt * MapRenderer's TILE_SIZE.
Pixel lat_lng_to_mercator(double lat, double lng, int zoom);

}  // namespace geo
