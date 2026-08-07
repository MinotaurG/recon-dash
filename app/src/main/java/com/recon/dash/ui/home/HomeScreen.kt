package com.recon.dash.ui.home

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.data.FavoritePlace
import com.recon.dash.data.FavoriteSlot
import com.recon.dash.ui.theme.*

data class HomeTile(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val onClick: () -> Unit,
    val appIconDrawable: Drawable? = null,
)

@Composable
fun HomeScreen(
    onSearchTap: () -> Unit,
    onFavoriteTap: (FavoritePlace) -> Unit,
    onFavoriteSlotTap: (FavoriteSlot) -> Unit,
    onDashTap: () -> Unit,
    onSettingsTap: () -> Unit = {},
    onRidesTap: () -> Unit = {},
    onGarageTap: () -> Unit = {},
    onPlacesTap: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val dashConnected by viewModel.dashConnected.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val musicApp by viewModel.musicAppPackage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val tiles = buildList {
        // Home
        add(HomeTile(
            id = "home",
            label = favorites[FavoriteSlot.HOME]?.name ?: "Home",
            icon = Icons.Rounded.Home,
            color = TileBlue,
            onClick = {
                favorites[FavoriteSlot.HOME]?.let { onFavoriteTap(it) }
                    ?: onFavoriteSlotTap(FavoriteSlot.HOME)
            },
        ))
        // Office
        add(HomeTile(
            id = "office",
            label = favorites[FavoriteSlot.OFFICE]?.name ?: "Office",
            icon = Icons.Rounded.Work,
            color = TileGreen,
            onClick = {
                favorites[FavoriteSlot.OFFICE]?.let { onFavoriteTap(it) }
                    ?: onFavoriteSlotTap(FavoriteSlot.OFFICE)
            },
        ))
        // Dash
        add(HomeTile(
            id = "dash",
            label = "Dash",
            icon = Icons.Rounded.Sensors,
            color = if (dashConnected) Success else Color(0xFF636366),
            onClick = onDashTap,
        ))
        // Music (show actual app icon if installed)
        val musicDrawable = getMusicAppIcon(context, musicApp)
        add(HomeTile(
            id = "music",
            label = musicAppLabel(musicApp),
            icon = musicAppIcon(musicApp),
            color = musicAppColor(musicApp),
            onClick = { launchMusicApp(context, musicApp) },
            appIconDrawable = musicDrawable,
        ))
        // Rides
        add(HomeTile(
            id = "rides",
            label = "Rides",
            icon = Icons.Rounded.Route,
            color = TileOrange,
            onClick = onRidesTap,
        ))
        // Garage
        add(HomeTile(
            id = "garage",
            label = "Garage",
            icon = Icons.Rounded.Build,
            color = Color(0xFF636366),
            onClick = onGarageTap,
        ))
        // Saved Places
        add(HomeTile(
            id = "places",
            label = "Places",
            icon = Icons.Rounded.Bookmarks,
            color = TileTeal,
            onClick = onPlacesTap,
        ))
        // Settings
        add(HomeTile(
            id = "settings",
            label = "Settings",
            icon = Icons.Rounded.Settings,
            color = Color(0xFF636366),
            onClick = onSettingsTap,
        ))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(16.dp))

            SearchBarTile(onClick = onSearchTap)

            Spacer(Modifier.height(20.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(tiles, key = { it.id }) { tile ->
                    CarPlayTile(
                        label = tile.label,
                        icon = tile.icon,
                        color = tile.color,
                        appIcon = tile.appIconDrawable,
                        onClick = tile.onClick,
                    )
                }
            }
        }

        // Persistent mini-player
        val np = nowPlaying
        if (np != null && np.isPlaying) {
            MiniPlayer(
                title = np.title,
                artist = np.artist,
                onTap = { launchMusicApp(context, musicApp) },
            )
        }
    }
}

private fun launchMusicApp(context: android.content.Context, preferredPackage: String) {
    if (preferredPackage.isNotBlank()) {
        val intent = context.packageManager.getLaunchIntentForPackage(preferredPackage)
        if (intent != null) {
            context.startActivity(intent)
            return
        }
    }
    val fallbackApps = listOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.amazon.mp3",
    )
    for (pkg in fallbackApps) {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            context.startActivity(intent)
            return
        }
    }
    val fallback = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_APP_MUSIC)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(fallback) }
}

private fun musicAppLabel(pkg: String): String = when {
    pkg.contains("spotify") -> "Spotify"
    pkg.contains("youtube.music") -> "YT Music"
    pkg.contains("amazon.mp3") -> "Amazon Music"
    pkg.contains("apple") -> "Apple Music"
    else -> "Music"
}

private fun musicAppIcon(pkg: String): ImageVector = when {
    pkg.contains("spotify") -> Icons.Rounded.Headphones
    pkg.contains("youtube") -> Icons.Rounded.PlayCircle
    pkg.contains("amazon") -> Icons.Rounded.LibraryMusic
    else -> Icons.Rounded.MusicNote
}

private fun musicAppColor(pkg: String): Color = when {
    pkg.contains("spotify") -> Color(0xFF1DB954)
    pkg.contains("youtube") -> Color(0xFFFF0000)
    pkg.contains("amazon") -> Color(0xFF25D1DA)
    else -> TilePurple
}

@Composable
private fun MiniPlayer(
    title: String,
    artist: String,
    onTap: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "miniPlayer")
    val barPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurfaceElevated)
            .clickable(onClick = onTap)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.height(20.dp),
        ) {
            val heights = listOf(barPhase, 1f - barPhase * 0.5f, barPhase * 0.7f)
            heights.forEach { h ->
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight(h.coerceIn(0.2f, 1f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(GoldAccent),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = OnSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
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

        Icon(
            imageVector = Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = OnSurface,
            modifier = Modifier.size(28.dp),
        )
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
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = OnSurfaceDim,
            modifier = Modifier.size(18.dp),
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
    icon: ImageVector,
    color: Color,
    appIcon: Drawable? = null,
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
                    colors = listOf(color, color.copy(alpha = 0.75f)),
                )
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (appIcon != null) {
            Image(
                bitmap = drawableToBitmap(appIcon).asImageBitmap(),
                contentDescription = label,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


private fun getMusicAppIcon(context: android.content.Context, pkg: String): Drawable? {
    if (pkg.isBlank()) {
        val fallbackApps = listOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.amazon.mp3",
        )
        for (app in fallbackApps) {
            val icon = runCatching { context.packageManager.getApplicationIcon(app) }.getOrNull()
            if (icon != null) return icon
        }
        return null
    }
    return runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
    val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
    val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}
