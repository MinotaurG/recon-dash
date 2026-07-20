package com.recon.dash.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
    val isNavigating by viewModel.isNavigating.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Recon Dash",
                color = OnSurface,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashPill(connected = dashConnected, onClick = onDashTap)
                IconPill(text = "Settings", onClick = onSettingsTap)
            }
        }

        Spacer(Modifier.height(24.dp))

        // Search bar
        val searchInteraction = remember { MutableInteractionSource() }
        val searchPressed by searchInteraction.collectIsPressedAsState()
        val searchScale by animateFloatAsState(
            if (searchPressed) 0.97f else 1f,
            animationSpec = spring(stiffness = Spring.StiffnessHigh),
            label = "searchScale",
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .scale(searchScale)
                .clip(RoundedCornerShape(14.dp))
                .background(DarkSurface)
                .clickable(interactionSource = searchInteraction, indication = null, onClick = onSearchTap)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Where to?",
                    color = OnSurfaceDim,
                    fontSize = 17.sp,
                )
            }
        }

        // Now Playing card (only when music is active)
        val np = nowPlaying
        if (np != null && np.isPlaying) {
            Spacer(Modifier.height(16.dp))
            NowPlayingCard(np.title, np.artist)
        }

        Spacer(Modifier.height(24.dp))

        // Home + Office row (always visible)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PinnedFavoriteCard(
                label = "Home",
                place = favorites[FavoriteSlot.HOME],
                onClick = {
                    favorites[FavoriteSlot.HOME]?.let { onFavoriteTap(it) }
                        ?: onFavoriteSlotTap(FavoriteSlot.HOME)
                },
                modifier = Modifier.weight(1f),
            )
            PinnedFavoriteCard(
                label = "Office",
                place = favorites[FavoriteSlot.OFFICE],
                onClick = {
                    favorites[FavoriteSlot.OFFICE]?.let { onFavoriteTap(it) }
                        ?: onFavoriteSlotTap(FavoriteSlot.OFFICE)
                },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(16.dp))

        // Custom favorites grid (only show saved ones + add button)
        val savedCustoms = CUSTOM_SLOTS.filter { favorites.containsKey(it) }
        val nextEmptySlot = CUSTOM_SLOTS.firstOrNull { !favorites.containsKey(it) }
        val showAdd = nextEmptySlot != null

        if (savedCustoms.isNotEmpty() || showAdd) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(savedCustoms, key = { it.name }) { slot ->
                    val place = favorites[slot]!!
                    CustomFavoriteCard(
                        place = place,
                        onClick = { onFavoriteTap(place) },
                    )
                }
                if (showAdd) {
                    item {
                        AddCard(onClick = { onFavoriteSlotTap(nextEmptySlot!!) })
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Ride history link
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(DarkSurface)
                .clickable(onClick = onRidesTap)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Ride History",
                    color = OnSurface,
                    fontSize = 15.sp,
                )
                Text(
                    text = ">",
                    color = OnSurfaceDim,
                    fontSize = 15.sp,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun DashPill(connected: Boolean, onClick: () -> Unit) {
    val dotColor by animateColorAsState(
        if (connected) Success else OnSurfaceDim.copy(alpha = 0.4f),
        label = "dashDot",
    )
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Text(text = "Dash", color = OnSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun IconPill(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text = text, color = OnSurfaceDim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PinnedFavoriteCard(
    label: String,
    place: FavoritePlace?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = if (label == "Home") TileBlue else TileGreen
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) 0.95f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "cardScale",
    )

    Column(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = if (place != null) 0.15f else 0.05f),
                        DarkSurface,
                    ),
                )
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accentColor.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (label == "Home") "H" else "W",
                color = accentColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            color = if (place != null) accentColor else OnSurfaceDim,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = place?.name ?: "Tap to set",
            color = if (place != null) OnSurface else OnSurfaceDim.copy(alpha = 0.4f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CustomFavoriteCard(place: FavoritePlace, onClick: () -> Unit) {
    val accentColor = slotColor(place.slot)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (isPressed) 0.95f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "customScale",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(accentColor.copy(alpha = 0.12f), DarkSurface),
                )
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accentColor))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = place.label,
            color = accentColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = place.name,
            color = OnSurface,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

@Composable
private fun NowPlayingCard(title: String, artist: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "music")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        TilePurple.copy(alpha = 0.08f),
                        DarkSurfaceElevated,
                    ),
                )
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .scale(pulse)
                .clip(RoundedCornerShape(10.dp))
                .background(TilePurple.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "N", color = TilePurple, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = artist,
                color = OnSurfaceDim,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AddCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .clickable(onClick = onClick)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "+",
                color = GoldAccent,
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
            )
            Text(
                text = "Add place",
                color = OnSurfaceDim,
                fontSize = 12.sp,
            )
        }
    }
}
