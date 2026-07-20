package com.recon.dash.ui.map

import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Composable wrapper for MapLibre MapView.
 *
 * Uses OpenStreetMap raster tiles as default style until PMTiles
 * vector style is configured. Provides the map instance via callback
 * for route overlays and markers.
 */
@Composable
fun MapViewComposable(
    modifier: Modifier = Modifier,
    centerLat: Double = 20.5937,
    centerLng: Double = 78.9629,
    zoom: Double = 4.0,
    onMapReady: ((MapLibreMap) -> Unit)? = null,
) {
    val context = LocalContext.current

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }

    DisposableEffect(mapView) {
        mapView.onCreate(null)
        mapView.getMapAsync { map ->
            map.setStyle(
                Style.Builder()
                    .fromUri("https://demotiles.maplibre.org/style.json")
            ) {
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                map.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(centerLat, centerLng))
                    .zoom(zoom)
                    .build()
                onMapReady?.invoke(map)
            }
        }
        onDispose {
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
    )
}
