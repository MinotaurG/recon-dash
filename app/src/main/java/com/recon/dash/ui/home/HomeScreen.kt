package com.recon.dash.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.data.FavoritePlace
import com.recon.dash.data.FavoriteSlot
import com.recon.dash.ui.theme.DarkBackground
import com.recon.dash.ui.theme.DarkSurface
import com.recon.dash.ui.theme.GoldAccent
import com.recon.dash.ui.theme.OnSurface

@Composable
fun HomeScreen(
    onSearchTap: () -> Unit,
    onFavoriteTap: (FavoritePlace) -> Unit,
    onFavoriteSlotTap: (FavoriteSlot) -> Unit,
    onDashTap: () -> Unit,
    onSettingsTap: () -> Unit = {},
    onRidesTap: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val dashConnected by viewModel.dashConnected.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "RECON DASH",
                color = OnSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DashIndicator(connected = dashConnected, onClick = onDashTap)
                SettingsButton(onClick = onSettingsTap)
            }
        }

        Spacer(Modifier.height(24.dp))

        SearchBar(onClick = onSearchTap)

        Spacer(Modifier.height(28.dp))

        Text(
            text = "Favourites",
            color = OnSurface.copy(alpha = 0.6f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(12.dp))

        FavoritesGrid(
            favorites = favorites,
            onFavoriteTap = onFavoriteTap,
            onEmptySlotTap = onFavoriteSlotTap,
        )

        Spacer(Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface)
                .clickable(onClick = onRidesTap)
                .padding(16.dp),
        ) {
            Text(
                text = "Ride History",
                color = OnSurface.copy(alpha = 0.7f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SettingsButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Settings",
            color = OnSurface.copy(alpha = 0.5f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun DashIndicator(connected: Boolean, onClick: () -> Unit) {
    val color = if (connected) Color(0xFF10B981) else OnSurface.copy(alpha = 0.3f)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = if (connected) "Dash" else "Dash",
            color = OnSurface.copy(alpha = 0.7f),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun SearchBar(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "Where to?",
            color = OnSurface.copy(alpha = 0.4f),
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun FavoritesGrid(
    favorites: Map<FavoriteSlot, FavoritePlace>,
    onFavoriteTap: (FavoritePlace) -> Unit,
    onEmptySlotTap: (FavoriteSlot) -> Unit,
) {
    val slots = FavoriteSlot.entries

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(slots, key = { it.name }) { slot ->
            val place = favorites[slot]
            FavoriteCard(
                slot = slot,
                place = place,
                onClick = {
                    if (place != null) onFavoriteTap(place)
                    else onEmptySlotTap(slot)
                },
            )
        }
    }
}

@Composable
private fun FavoriteCard(
    slot: FavoriteSlot,
    place: FavoritePlace?,
    onClick: () -> Unit,
) {
    val label = place?.label ?: slotDefaultLabel(slot)
    val name = place?.name

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(
            text = label,
            color = if (place != null) GoldAccent else OnSurface.copy(alpha = 0.4f),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (name != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = name,
                color = OnSurface.copy(alpha = 0.8f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Tap to set",
                color = OnSurface.copy(alpha = 0.25f),
                fontSize = 12.sp,
            )
        }
    }
}

private fun slotDefaultLabel(slot: FavoriteSlot): String = when (slot) {
    FavoriteSlot.HOME -> "Home"
    FavoriteSlot.OFFICE -> "Office"
    FavoriteSlot.CUSTOM_1 -> "Custom 1"
    FavoriteSlot.CUSTOM_2 -> "Custom 2"
    FavoriteSlot.CUSTOM_3 -> "Custom 3"
    FavoriteSlot.CUSTOM_4 -> "Custom 4"
    FavoriteSlot.CUSTOM_5 -> "Custom 5"
}
