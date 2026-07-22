// Host-runnable parity test for the C++ geo core (no JNI, no device).
// Compile & run:  c++ -std=c++17 geo.cpp geo_parity_test.cpp -o /tmp/geo_test && /tmp/geo_test
//
// Proves geo::decode_polyline + geo::lat_lng_to_mercator match an INDEPENDENT
// reference implementation of the same Kotlin formulas (PolylineCodec.kt / Mercator.kt).
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "geo.hpp"

namespace {
constexpr double kPi = 3.14159265358979323846;

// Independent reference decoder (Google/OSRM algorithm), written from the spec, not
// copied from geo.cpp — so agreement is a real cross-check.
std::vector<geo::LatLng> ref_decode(const std::string& s, int precision) {
    std::vector<geo::LatLng> out;
    double factor = std::pow(10.0, precision);
    std::size_t i = 0, n = s.size();
    long lat = 0, lng = 0;
    while (i < n) {
        long shift = 0, res = 0; int c;
        do { c = (int)(unsigned char)s[i++] - 63; res |= (long)(c & 0x1f) << shift; shift += 5; }
        while (c >= 0x20 && i < n);
        lat += (res & 1) ? ~(res >> 1) : (res >> 1);
        if (i >= n) break;
        shift = 0; res = 0;
        do { c = (int)(unsigned char)s[i++] - 63; res |= (long)(c & 0x1f) << shift; shift += 5; }
        while (c >= 0x20 && i < n);
        lng += (res & 1) ? ~(res >> 1) : (res >> 1);
        out.push_back({lat / factor, lng / factor});
    }
    return out;
}

geo::Pixel ref_project(double lat, double lng, int zoom) {
    double nn = (double)(1u << zoom);
    double x = (lng + 180.0) / 360.0 * nn;
    double r = lat * kPi / 180.0;
    double y = (1.0 - std::log(std::tan(r) + 1.0 / std::cos(r)) / kPi) / 2.0 * nn;
    return {x * geo::TILE_SIZE, y * geo::TILE_SIZE};
}

int failures = 0;
void check(bool ok, const char* what) {
    if (!ok) { std::printf("  FAIL: %s\n", what); ++failures; }
}
}  // namespace

int main() {
    // Google's canonical example "_p~iF~ps|U_ulLnnqC_mqNvxq`@" (precision 5)
    // decodes to (38.5,-120.2),(40.7,-120.95),(43.252,-126.453).
    {
        const std::string enc = "_p~iF~ps|U_ulLnnqC_mqNvxq`@";
        auto got = geo::decode_polyline(enc, 5);
        check(got.size() == 3, "canonical size == 3");
        const double exp[3][2] = {{38.5, -120.2}, {40.7, -120.95}, {43.252, -126.453}};
        for (std::size_t i = 0; i < got.size() && i < 3; ++i) {
            check(std::fabs(got[i].lat - exp[i][0]) < 1e-6, "canonical lat");
            check(std::fabs(got[i].lng - exp[i][1]) < 1e-6, "canonical lng");
        }
    }

    // Precision-6 (Valhalla) round-trip against the independent reference.
    {
        // Encoded from a Hyderabad-ish track at precision 6.
        const std::string enc = "_kb_Cgw_xM_ibE_ibE~hbE_ibE";
        for (int prec : {5, 6}) {
            auto a = geo::decode_polyline(enc, prec);
            auto b = ref_decode(enc, prec);
            check(a.size() == b.size(), "decode size matches reference");
            for (std::size_t i = 0; i < a.size() && i < b.size(); ++i) {
                check(std::fabs(a[i].lat - b[i].lat) < 1e-9, "decode lat matches reference");
                check(std::fabs(a[i].lng - b[i].lng) < 1e-9, "decode lng matches reference");
            }
        }
    }

    // Projection parity across zooms and a spread of coordinates.
    {
        const double coords[][2] = {
            {17.385, 78.4867},   // Hyderabad
            {0.0, 0.0}, {51.5074, -0.1278}, {-33.8688, 151.2093}, {40.7128, -74.0060},
        };
        for (auto& c : coords) {
            for (int z : {11, 15, 17, 19}) {
                auto p = geo::lat_lng_to_mercator(c[0], c[1], z);
                auto r = ref_project(c[0], c[1], z);
                check(std::fabs(p.x - r.x) < 1e-6, "project x matches reference");
                check(std::fabs(p.y - r.y) < 1e-6, "project y matches reference");
            }
        }
    }

    // Truncated input must not read past the end (guard parity with Kotlin).
    {
        // point 1 is complete; the trailing "_ulL" is a lat group with no lng → dropped.
        auto got = geo::decode_polyline("_p~iF~ps|U_ulL", 5);
        check(got.size() == 1, "truncated trailing lat group dropped");
        check(geo::decode_polyline("", 5).empty(), "empty input -> empty");
    }

    if (failures == 0) { std::printf("PARITY OK — all checks passed\n"); return 0; }
    std::printf("PARITY FAILED — %d check(s)\n", failures);
    return 1;
}
