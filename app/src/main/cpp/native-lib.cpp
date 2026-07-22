// JNI glue ONLY. All real work lives in geo.cpp (JNI-free, unit-testable).
// One crossing per polyline: string in, flat float[] out. Never per-coordinate.
#include <jni.h>

#include <string>
#include <vector>

#include "geo.hpp"

extern "C" {

// FloatArray decodeAndProject(String encoded, int precision, int zoom)
// Returns [x0, y0, x1, y1, ...] absolute Mercator pixels. Empty array on empty input.
JNIEXPORT jfloatArray JNICALL
Java_com_recon_dash_util_NativeGeo_decodeAndProject(
        JNIEnv* env, jobject /* this */, jstring encoded, jint precision, jint zoom) {
    const char* chars = env->GetStringUTFChars(encoded, nullptr);
    std::string enc(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(encoded, chars);

    std::vector<geo::LatLng> pts = geo::decode_polyline(enc, static_cast<int>(precision));

    const jsize count = static_cast<jsize>(pts.size() * 2);
    jfloatArray out = env->NewFloatArray(count);
    if (out == nullptr) return nullptr;  // OOM — pending exception thrown by JVM
    if (count == 0) return out;

    // Fill a native buffer, then one bulk copy across the boundary.
    std::vector<jfloat> flat(static_cast<std::size_t>(count));
    for (std::size_t i = 0; i < pts.size(); ++i) {
        const geo::Pixel px = geo::lat_lng_to_mercator(pts[i].lat, pts[i].lng, static_cast<int>(zoom));
        flat[i * 2] = static_cast<jfloat>(px.x);
        flat[i * 2 + 1] = static_cast<jfloat>(px.y);
    }
    env->SetFloatArrayRegion(out, 0, count, flat.data());
    return out;
}

// FloatArray decodeLatLng(String encoded, int precision)
// Returns raw [lat0, lng0, lat1, lng1, ...] — for callers that need coords, not pixels
// (Router, OsrmClient). Lets the native decoder replace PolylineCodec.decode directly.
JNIEXPORT jfloatArray JNICALL
Java_com_recon_dash_util_NativeGeo_decodeLatLng(
        JNIEnv* env, jobject /* this */, jstring encoded, jint precision) {
    const char* chars = env->GetStringUTFChars(encoded, nullptr);
    std::string enc(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(encoded, chars);

    std::vector<geo::LatLng> pts = geo::decode_polyline(enc, static_cast<int>(precision));

    const jsize count = static_cast<jsize>(pts.size() * 2);
    jfloatArray out = env->NewFloatArray(count);
    if (out == nullptr) return nullptr;
    if (count == 0) return out;

    std::vector<jfloat> flat(static_cast<std::size_t>(count));
    for (std::size_t i = 0; i < pts.size(); ++i) {
        flat[i * 2] = static_cast<jfloat>(pts[i].lat);
        flat[i * 2 + 1] = static_cast<jfloat>(pts[i].lng);
    }
    env->SetFloatArrayRegion(out, 0, count, flat.data());
    return out;
}

}  // extern "C"
