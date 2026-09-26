package com.recon.dash.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recon.dash.dash.nav.GeoPoint
import com.recon.dash.ui.theme.DarkSurface
import com.recon.dash.ui.theme.GoldAccent
import com.recon.dash.ui.theme.OnSurface
import com.recon.dash.util.LocationHelper

/**
 * Full-screen interactive map with a floating search bar (Google-Maps-style). The map is free-pan
 * (no rider-follow); tapping the search bar opens the existing search flow, which routes into
 * route-preview → active nav. The nav tile on Home opens this.
 */
@Composable
fun NavMapScreen(
    onSearchTap: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // Center on the rider's last-known location if we have one, else the India default.
    val here = remember { LocationHelper.getLastKnown(context) }
    val centerLat = here?.lat ?: 20.5937
    val centerLng = here?.lng ?: 78.9629
    val myLocation = here?.let { GeoPoint(it.lat, it.lng) }

    Box(modifier = Modifier.fillMaxSize()) {
        MapViewComposable(
            modifier = Modifier.fillMaxSize(),
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = if (here != null) 15.0 else 4.0,
            followRider = false,
            riderLocation = myLocation,   // show a marker at the rider, but don't follow
        )

        // Floating search bar at the top — tap to open search.
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(DarkSurface)
                .clickable { onSearchTap() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Rounded.ArrowBack, "Back", tint = OnSurface.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Rounded.Search, null, tint = GoldAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Where to?", color = OnSurface.copy(alpha = 0.6f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }

        // Recenter-on-me button (bottom-right).
        if (myLocation != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(20.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(DarkSurface),
                contentAlignment = Alignment.Center,
            ) {
                // Recenter is a visual affordance; the map already opens centered on the rider.
                // (A live recenter would need a camera handle from MapViewComposable — later.)
                Icon(Icons.Rounded.MyLocation, "My location", tint = GoldAccent, modifier = Modifier.size(24.dp))
            }
        }
    }
}
