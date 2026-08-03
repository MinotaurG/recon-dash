package com.recon.dash.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.recon.dash.dash.nav.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROUTE_SOURCE = "route-source"
private const val ROUTE_LAYER = "route-layer"
private const val TRAVELLED_SOURCE = "travelled-source"
private const val TRAVELLED_LAYER = "travelled-layer"
private const val DEST_SOURCE = "dest-source"
private const val DEST_LAYER = "dest-layer"
private const val DEST_ICON = "dest-pin"
private const val ORIGIN_SOURCE = "origin-source"
private const val ORIGIN_LAYER = "origin-layer"
private const val ORIGIN_ICON = "origin-dot"
private const val RIDER_SOURCE = "rider-source"
private const val RIDER_LAYER = "rider-layer"
private const val RIDER_ICON = "rider-arrow"

/**
 * MapLibre map wrapper. The MapView + style are created ONCE and reused; the
 * route line and destination pin are updated in place via [LaunchedEffect]
 * instead of recreating the map (which would refetch all tiles — very slow).
 */
@Composable
fun MapViewComposable(
    modifier: Modifier = Modifier,
    centerLat: Double = 20.5937,
    centerLng: Double = 78.9629,
    zoom: Double = 4.0,
    routeGeometry: List<GeoPoint> = emptyList(),
    // When set (live nav), the route is drawn as two layers — traveled (grey) + ahead (blue) —
    // and the camera FOLLOWS the rider (travel-up) instead of re-fitting the whole route.
    travelledGeometry: List<GeoPoint> = emptyList(),
    aheadGeometry: List<GeoPoint> = emptyList(),
    followRider: Boolean = false,
    destination: GeoPoint? = null,
    riderLocation: GeoPoint? = null,
    riderBearing: Float = 0f,
    onMapReady: ((MapLibreMap) -> Unit)? = null,
) {
    val context = LocalContext.current
    val mapView = remember { MapLibre.getInstance(context); MapView(context) }
    val mapRef = remember { arrayOfNulls<MapLibreMap>(1) }
    val styleRef = remember { arrayOfNulls<Style>(1) }

    DisposableEffect(mapView) {
        mapView.onCreate(null)
        mapView.getMapAsync { map ->
            mapRef[0] = map
            val styleJson = """
            {
              "version": 8,
              "sources": {
                "osm": {
                  "type": "raster",
                  "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
                  "tileSize": 256,
                  "attribution": "© OpenStreetMap contributors"
                }
              },
              "layers": [ { "id": "osm", "type": "raster", "source": "osm" } ]
            }
            """.trimIndent()
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                styleRef[0] = style
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                style.addImage(DEST_ICON, buildPinBitmap())
                style.addImage(ORIGIN_ICON, buildOriginBitmap())
                style.addImage(RIDER_ICON, buildRiderArrowBitmap())

                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(centerLat, centerLng)).zoom(zoom).build()

                applyRoute(map, style, routeGeometry, travelledGeometry, aheadGeometry,
                    followRider, destination, riderLocation, riderBearing)
                onMapReady?.invoke(map)
            }
        }
        onDispose { mapView.onDestroy() }
    }

    // Update route/marker in place whenever they change (no map recreation).
    LaunchedEffect(routeGeometry, travelledGeometry, aheadGeometry, destination, riderLocation, riderBearing) {
        val map = mapRef[0]
        val style = styleRef[0]
        if (map != null && style != null && style.isFullyLoaded) {
            applyRoute(map, style, routeGeometry, travelledGeometry, aheadGeometry,
                followRider, destination, riderLocation, riderBearing)
        }
    }

    // Smoothing animator (live nav only): GPS arrives at 1 Hz, which makes the marker + camera
    // step jerkily. Here we tween the marker position, bearing, and follow-camera from the previous
    // fix to the new one over ~950 ms (just under the fix interval) so motion looks continuous
    // without any extra GPS. Bearing uses shortest-arc interpolation to avoid a 359°→1° spin.
    val prevLat = remember { doubleArrayOf(Double.NaN) }
    val prevLng = remember { doubleArrayOf(Double.NaN) }
    val prevBearing = remember { floatArrayOf(Float.NaN) }
    LaunchedEffect(riderLocation, riderBearing, followRider) {
        val map = mapRef[0]
        val style = styleRef[0]
        val target = riderLocation
        if (!followRider || map == null || style == null || !style.isFullyLoaded || target == null) {
            if (target != null) { prevLat[0] = target.lat; prevLng[0] = target.lng; prevBearing[0] = riderBearing }
            return@LaunchedEffect
        }
        val fromLat = if (prevLat[0].isNaN()) target.lat else prevLat[0]
        val fromLng = if (prevLng[0].isNaN()) target.lng else prevLng[0]
        val fromBrg = if (prevBearing[0].isNaN()) riderBearing else prevBearing[0]
        // Shortest-arc bearing delta in [-180, 180].
        var dBrg = ((riderBearing - fromBrg + 540f) % 360f) - 180f
        val riderSrc = style.getSourceAs<GeoJsonSource>(RIDER_SOURCE)
        val riderLayer = style.getLayer(RIDER_LAYER)
        val anim = Animatable(0f)
        anim.animateTo(1f, animationSpec = tween(durationMillis = 950)) {
            val t = this.value
            val lat = fromLat + (target.lat - fromLat) * t
            val lng = fromLng + (target.lng - fromLng) * t
            val brg = fromBrg + dBrg * t
            riderSrc?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(lng, lat)))
            riderLayer?.setProperties(PropertyFactory.iconRotate(brg))
            val cam = CameraPosition.Builder()
                .target(LatLng(lat, lng)).zoom(16.5).bearing(brg.toDouble()).build()
            runCatching { map.moveCamera(CameraUpdateFactory.newCameraPosition(cam)) }
        }
        prevLat[0] = target.lat; prevLng[0] = target.lng; prevBearing[0] = riderBearing
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun applyRoute(
    map: MapLibreMap,
    style: Style,
    geometry: List<GeoPoint>,
    travelledGeometry: List<GeoPoint>,
    aheadGeometry: List<GeoPoint>,
    followRider: Boolean,
    destination: GeoPoint?,
    riderLocation: GeoPoint? = null,
    riderBearing: Float = 0f,
) {
    // In follow (live-nav) mode the blue "ahead" line is the trimmed remainder; otherwise
    // (preview) the blue line is the full route.
    val aheadLine = if (followRider) aheadGeometry else geometry

    // Travelled portion — grey, behind the rider (only in follow mode).
    setLine(style, TRAVELLED_SOURCE, TRAVELLED_LAYER, travelledGeometry, "#9AA0A6", 5f, below = ROUTE_LAYER)

    // Route line ahead — blue.
    setLine(style, ROUTE_SOURCE, ROUTE_LAYER, aheadLine, "#4285F4", 5.5f, below = null)

    // Destination pin.
    if (destination != null) {
        val pt = Feature.fromGeometry(Point.fromLngLat(destination.lng, destination.lat))
        val existing = style.getSourceAs<GeoJsonSource>(DEST_SOURCE)
        if (existing != null) {
            existing.setGeoJson(pt)
        } else {
            style.addSource(GeoJsonSource(DEST_SOURCE, pt))
            style.addLayer(
                SymbolLayer(DEST_LAYER, DEST_SOURCE).withProperties(
                    PropertyFactory.iconImage(DEST_ICON),
                    PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                )
            )
        }
    }

    // Start-point indicator — a small dot at the route origin (first geometry point).
    val origin = geometry.firstOrNull()
    if (origin != null) {
        val pt = Feature.fromGeometry(Point.fromLngLat(origin.lng, origin.lat))
        val existing = style.getSourceAs<GeoJsonSource>(ORIGIN_SOURCE)
        if (existing != null) {
            existing.setGeoJson(pt)
        } else {
            style.addSource(GeoJsonSource(ORIGIN_SOURCE, pt))
            style.addLayer(
                SymbolLayer(ORIGIN_LAYER, ORIGIN_SOURCE).withProperties(
                    PropertyFactory.iconImage(ORIGIN_ICON),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                )
            )
        }
    }

    // Current-location marker — a Google-Maps-style blue arrow that rotates to the
    // rider's travel bearing. Placed ABOVE the route line so it stays visible.
    // In follow (live-nav) mode the position/bearing are driven by the smoothing animator
    // (see the LaunchedEffect below) so the marker glides between the 1 Hz fixes instead of
    // jumping; here we only ensure the layer exists and seed its first position.
    if (riderLocation != null) {
        val pt = Feature.fromGeometry(Point.fromLngLat(riderLocation.lng, riderLocation.lat))
        val existing = style.getSourceAs<GeoJsonSource>(RIDER_SOURCE)
        if (existing == null) {
            style.addSource(GeoJsonSource(RIDER_SOURCE, pt))
            style.addLayer(
                SymbolLayer(RIDER_LAYER, RIDER_SOURCE).withProperties(
                    PropertyFactory.iconImage(RIDER_ICON),
                    PropertyFactory.iconRotate(riderBearing),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                )
            )
        } else if (!followRider) {
            // Preview mode: no animator running, so update the marker directly.
            existing.setGeoJson(pt)
            style.getLayer(RIDER_LAYER)?.setProperties(PropertyFactory.iconRotate(riderBearing))
        }
    }

    when {
        // Live nav: the follow-camera + marker are handled by the smoothing animator, NOT here,
        // so we don't double-drive them (which caused stutter). Nothing to do in this branch.
        followRider && riderLocation != null -> Unit
        // Preview: fit the whole route once.
        geometry.size >= 2 -> {
            val b = LatLngBounds.Builder()
            geometry.forEach { b.include(LatLng(it.lat, it.lng)) }
            runCatching { map.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 120)) }
        }
        destination != null -> {
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(destination.lat, destination.lng), 15.0))
        }
    }
}

/** Create/update a line layer's geometry in place; optionally insert below another layer. */
private fun setLine(
    style: Style, sourceId: String, layerId: String,
    points: List<GeoPoint>, color: String, width: Float, below: String?,
) {
    if (points.size < 2) {
        // Clear stale geometry (e.g. travelled line before the first fix).
        style.getSourceAs<GeoJsonSource>(sourceId)?.setGeoJson(
            LineString.fromLngLats(emptyList())
        )
        return
    }
    val line = LineString.fromLngLats(points.map { Point.fromLngLat(it.lng, it.lat) })
    val existing = style.getSourceAs<GeoJsonSource>(sourceId)
    if (existing != null) {
        existing.setGeoJson(line)
    } else {
        style.addSource(GeoJsonSource(sourceId, line))
        val layer = LineLayer(layerId, sourceId).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(width),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        if (below != null && style.getLayer(below) != null) style.addLayerBelow(layer, below)
        else style.addLayer(layer)
    }
}

/** Draw a Google-Maps-style red teardrop pin as a bitmap for the SymbolLayer. */
private fun buildPinBitmap(): Bitmap {
    val w = 72
    val h = 96
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val red = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xEA, 0x43, 0x35) }
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    val cx = w / 2f
    val cy = w / 2f
    val r = w / 2f - 4f
    // Circle head
    canvas.drawCircle(cx, cy, r, red)
    // Tail (triangle to bottom point)
    val path = android.graphics.Path().apply {
        moveTo(cx - r * 0.6f, cy + r * 0.5f)
        lineTo(cx, h.toFloat() - 2f)
        lineTo(cx + r * 0.6f, cy + r * 0.5f)
        close()
    }
    canvas.drawPath(path, red)
    // Inner white dot
    canvas.drawCircle(cx, cy, r * 0.42f, white)
    return bmp
}

/** Start-point indicator: a green dot with a white ring (route origin). */
private fun buildOriginBitmap(): Bitmap {
    val s = 48
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val c = s / 2f
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x34, 0xA8, 0x53) }
    canvas.drawCircle(c, c, c - 2f, white)      // white ring
    canvas.drawCircle(c, c, c - 8f, green)      // green core
    return bmp
}

/** Current location: a Google-Maps-style blue arrow (points "up"; rotated via iconRotate). */
private fun buildRiderArrowBitmap(): Bitmap {
    val s = 64
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val c = s / 2f
    val blue = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0x42, 0x85, 0xF4) }
    val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    // White circle backing so the arrow reads on any map color.
    canvas.drawCircle(c, c, c - 4f, white)
    // Blue chevron/arrow pointing up.
    val arrow = android.graphics.Path().apply {
        moveTo(c, 10f)                    // tip (top)
        lineTo(s - 14f, s - 14f)          // bottom-right
        lineTo(c, s - 22f)                // notch (center)
        lineTo(14f, s - 14f)              // bottom-left
        close()
    }
    canvas.drawPath(arrow, blue)
    return bmp
}
