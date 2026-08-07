package com.recon.dash.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.data.CUSTOM_SLOTS
import com.recon.dash.data.FavoriteSlot
import com.recon.dash.data.presetName
import com.recon.dash.ui.theme.DarkBackground
import com.recon.dash.ui.theme.DarkSurface
import com.recon.dash.ui.theme.GoldAccent
import com.recon.dash.ui.theme.OnSurface
import com.recon.dash.ui.theme.OnSurfaceDim

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onWallpaperTap: () -> Unit = {},
    onRegionTap: () -> Unit = {},
    onManagePlacesTap: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val ssid by viewModel.ssid.collectAsStateWithLifecycle()
    val voiceMode by viewModel.voiceMode.collectAsStateWithLifecycle()
    val musicApp by viewModel.musicApp.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val speedAlert by viewModel.speedAlert.collectAsStateWithLifecycle()
    val projectWhenIdle by viewModel.projectWhenIdle.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Settings",
                color = OnSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = OnSurfaceDim,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // -- Home Screen --
        SectionHeader("Home Screen")
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SettingsRow(
                title = "Default music app",
                subtitle = when {
                    musicApp.contains("spotify") -> "Spotify"
                    musicApp.contains("youtube.music") -> "YT Music"
                    musicApp.contains("amazon.mp3") -> "Amazon Music"
                    musicApp.contains("apple") -> "Apple Music"
                    musicApp.isBlank() -> "Auto-detect"
                    else -> musicApp
                },
                onClick = { viewModel.cycleMusicApp() },
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SettingsRow(
                title = "Manage saved places",
                subtitle = "Edit or clear Home, Office and custom places",
                onClick = onManagePlacesTap,
            )
        }

        Spacer(Modifier.height(20.dp))

        // -- Dash Connection --
        SectionHeader("Dash Connection")
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SettingsRow(
                title = "Saved SSID",
                subtitle = ssid.ifBlank { "Not set, will discover by prefix" },
            )
            SettingsRow(
                title = "Forget dash",
                subtitle = "Next connect will rediscover the dash WiFi",
                onClick = { viewModel.forgetDash() },
            )
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("Voice Navigation")
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SettingsRow(
                title = "Mode",
                subtitle = when (voiceMode) {
                    "OFF" -> "Silent"
                    "CHIME" -> "Beep before turns"
                    "FULL" -> "Spoken directions"
                    else -> voiceMode
                },
                onClick = { viewModel.cycleVoiceMode() },
            )
        }

        Spacer(Modifier.height(20.dp))

        // -- Riding --
        SectionHeader("Riding")
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SettingsRow(
                title = "Speed alert",
                subtitle = if (speedAlert == 0) "Off" else "$speedAlert km/h",
                onClick = { viewModel.cycleSpeedAlert() },
            )
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("Display")
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SettingsRow(
                title = "Theme",
                subtitle = when (themeMode) {
                    "LIGHT" -> "Light"
                    "DARK" -> "Dark"
                    else -> "Auto (system)"
                },
                onClick = { viewModel.cycleTheme() },
            )
            SettingsRow(
                title = "Dash wallpaper",
                subtitle = "Shown when not navigating",
                onClick = onWallpaperTap,
            )
            SettingsToggleRow(
                title = "Show wallpaper on dash",
                subtitle = if (projectWhenIdle) "Replaces the dash RPM screen when idle"
                           else "Dash keeps its RPM screen; map shows only during navigation",
                checked = projectWhenIdle,
                onToggle = { viewModel.toggleProjectWhenIdle() },
            )
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("Data")
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SettingsRow(
                title = "Routing graph",
                subtitle = if (viewModel.hasGraph) "Installed" else "Not downloaded",
                onClick = onRegionTap,
            )
            SettingsRow(
                title = "Tile cache",
                subtitle = viewModel.tileCacheSize,
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

private fun slotLabel(slot: FavoriteSlot): String = slot.presetName()

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = OnSurface,
                fontSize = 15.sp,
            )
            Text(
                text = subtitle,
                color = OnSurface.copy(alpha = 0.45f),
                fontSize = 13.sp,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedTrackColor = GoldAccent,
                uncheckedTrackColor = OnSurface.copy(alpha = 0.1f),
            ),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = OnSurface.copy(alpha = 0.5f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkSurface)
            .padding(vertical = 4.dp),
        content = content,
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = title,
            color = OnSurface,
            fontSize = 15.sp,
        )
        Text(
            text = subtitle,
            color = OnSurface.copy(alpha = 0.45f),
            fontSize = 13.sp,
        )
    }
}
