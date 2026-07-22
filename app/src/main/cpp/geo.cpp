#include "geo.hpp"

#include <cmath>

namespace geo {

namespace {
constexpr double kPi = 3.14159265358979323846;
}  // namespace

// Faithful port of PolylineCodec.decode (dash/nav/PolylineCodec.kt). Same varint /
// zigzag scheme, same truncated-input guard (a lat group that consumes the last char
// with no lng following is dropped rather than read past the end).
std::vector<LatLng> decode_polyline(const std::string& encoded, int precision) {
    std::vector<LatLng> points;
    const double factor = std::pow(10.0, static_cast<double>(precision));
    const std::size_t len = encoded.size();
    std::size_t index = 0;
    long lat = 0;
    long lng = 0;

    while (index < len) {
        // latitude delta
        int shift = 0;
        long result = 0;
        int b;
        do {
            b = static_cast<int>(static_cast<unsigned char>(encoded[index++])) - 63;
            result |= static_cast<long>(b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20 && index < len);
        lat += (result & 1) ? ~(result >> 1) : (result >> 1);

        // Truncated input: no longitude group follows — stop cleanly.
        if (index >= len) break;

        // longitude delta
        shift = 0;
        result = 0;
        do {
            b = static_cast<int>(static_cast<unsigned char>(encoded[index++])) - 63;
            result |= static_cast<long>(b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20 && index < len);
        lng += (result & 1) ? ~(result >> 1) : (result >> 1);

        points.push_back(LatLng{static_cast<double>(lat) / factor,
                                static_cast<double>(lng) / factor});
    }
    return points;
}

// Port of Mercator.lngToTileX / latToTileY, then * TILE_SIZE to yield pixels.
// n = 2^zoom tiles per axis; tan(r) + 1/cos(r) == the standard sec form in Mercator.kt.
Pixel lat_lng_to_mercator(double lat, double lng, int zoom) {
    const double n = static_cast<double>(1u << zoom);
    const double x_tile = (lng + 180.0) / 360.0 * n;
    const double r = lat * kPi / 180.0;
    const double y_tile = (1.0 - std::log(std::tan(r) + 1.0 / std::cos(r)) / kPi) / 2.0 * n;
    return Pixel{x_tile * TILE_SIZE, y_tile * TILE_SIZE};
}

}  // namespace geo
