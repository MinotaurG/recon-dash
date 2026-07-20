package com.recon.dash.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.data.CUSTOM_SLOTS
import com.recon.dash.data.FavoritePlace
import com.recon.dash.data.FavoriteSlot
import com.recon.dash.ui.theme.*

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
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        // Search bar (primary action)
        SearchBarTile(onClick = onSearchTap)

        Spacer(Modifier.height(20.dp))

        // Feature + Favorites grid
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Pinned favorites: Home + Office
            item {
                CarPlayTile(
                    label = favorites[FavoriteSlot.HOME]?.name ?: "Home",
                    icon = "H",
                    color = TileBlue,
                    subtitle = if (favorites[FavoriteSlot.HOME] != null) "Home" else "Tap to set",
                    onClick = {
                        favorites[FavoriteSlot.HOME]?.let { onFavoriteTap(it) }
                            ?: onFavoriteSlotTap(FavoriteSlot.HOME)
                    },
                )
            }
            item {
                CarPlayTile(
                    label = favorites[FavoriteSlot.OFFICE]?.name ?: "Office",
                    icon = "W",
                    color = TileGreen,
                    subtitle = if (favorites[FavoriteSlot.OFFICE] != null) "Office" else "Tap to set",
                    onClick = {
                        favorites[FavoriteSlot.OFFICE]?.let { onFavoriteTap(it) }
                            ?: onFavoriteSlotTap(FavoriteSlot.OFFICE)
                    },
                )
            }

            // Feature tiles
            item {
                CarPlayTile(
                    label = "Dash",
                    icon = "D",
                    color = if (dashConnected) Success else Color(0xFF636366),
                    subtitle = if (dashConnected) "Connected" else "Not connected",
                    onClick = onDashTap,
                )
            }
            item {
                CarPlayTile(
                    label = "Rides",
                    icon = "R",
                    color = TileOrange,
                    onClick = onRidesTap,
                )
            }

            // Now Playing (if active)
            val np = nowPlaying
            if (np != null && np.isPlaying) {
                item(span = { GridItemSpan(2) }) {
                    NowPlayingTile(title = np.title, artist = np.artist)
                }
            }

            // Custom favorites
            val savedCustoms = CUSTOM_SLOTS.filter { favorites.containsKey(it) }
            items(savedCustoms, key = { it.name }) { slot ->
                val place = favorites[slot]!!
                CarPlayTile(
                    label = place.name,
                    icon = place.label.first().uppercase(),
                    color = slotColor(slot),
                    subtitle = place.label,
                    onClick = { onFavoriteTap(place) },
                )
            }

            // Add new favorite
            val nextEmpty = CUSTOM_SLOTS.firstOrNull { !favorites.containsKey(it) }
            if (nextEmpty != null) {
                item {
                    AddTile(onClick = { onFavoriteSlotTap(nextEmpty) })
                }
            }

            // Settings at the end
            item {
                CarPlayTile(
                    label = "Settings",
                    icon = "S",
                    color = Color(0xFF636366),
                    onClick = onSettingsTap,
                )
            }
        }
    }
}

@Composable
private fun SearchBarTile(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "searchScale",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "O",
            color = OnSurfaceDim,
            fontSize = 14.sp,
        )
        Text(
            text = "Search or enter destination",
            color = OnSurfaceDim.copy(alpha = 0.7f),
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun CarPlayTile(
    label: String,
    icon: String,
    color: Color,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "tileScale",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .scale(scale)
            .shadow(8.dp, RoundedCornerShape(22.dp), spotColor = color.copy(alpha = 0.3f))
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(color, color.copy(alpha = 0.8f)),
                )
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = icon,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column {
            Text(
                text = label,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NowPlayingTile(title: String, artist: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "music")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(TilePurple, TilePink.copy(alpha = 0.8f)),
                )
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .scale(pulse)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "N", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) 0.92f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "addScale",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .scale(scale)
            .clip(RoundedCornerShape(22.dp))
            .background(DarkSurface)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(OnSurfaceDim.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "+", color = OnSurfaceDim, fontSize = 22.sp, fontWeight = FontWeight.Light)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Add Place",
                color = OnSurfaceDim,
                fontSize = 12.sp,
            )
        }
    }
}

private fun slotColor(slot: FavoriteSlot): Color = when (slot) {
    FavoriteSlot.HOME -> TileBlue
    FavoriteSlot.OFFICE -> TileGreen
    FavoriteSlot.CUSTOM_1 -> TileOrange
    FavoriteSlot.CUSTOM_2 -> TilePurple
    FavoriteSlot.CUSTOM_3 -> TilePink
    FavoriteSlot.CUSTOM_4 -> TileTeal
}
