package com.recon.dash.ui.search

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.recon.dash.data.FavoriteSlot
import com.recon.dash.data.presetName
import com.recon.dash.search.SearchResult
import com.recon.dash.ui.theme.*

@Composable
fun SearchScreen(
    onResultTap: (SearchResult) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val saveSlot = viewModel.saveToSlot

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))

        if (saveSlot != null) {
            Text(
                text = "Saving to: ${slotLabel(saveSlot)}",
                color = GoldAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(12.dp))
        }

        // Search field with close button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField(
                value = query,
                onValueChange = { viewModel.updateQuery(it) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
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

        Spacer(Modifier.height(16.dp))

        if (error != null) {
            Text(
                text = error ?: "",
                color = OnSurfaceDim,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
        }

        // Empty query → show recent searches. Otherwise show live results.
        val showRecents = query.isBlank() && recents.isNotEmpty()

        if (results.isEmpty() && query.isNotBlank() && !isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "No results found", color = OnSurfaceDim, fontSize = 14.sp)
            }
        }

        if (showRecents) {
            Text(
                text = "Recent",
                color = OnSurfaceDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        val displayList = if (showRecents) recents else results

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            itemsIndexed(displayList, key = { index, it -> "$index-${it.location.lat},${it.location.lng}" }) { index, result ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically(
                        initialOffsetY = { 20 },
                        animationSpec = tween(200, delayMillis = index * 30),
                    ),
                ) {
                    ResultRow(
                        result = result,
                        saveMode = saveSlot != null,
                        recent = showRecents,
                        onClick = {
                            if (saveSlot != null) {
                                viewModel.saveAsFavorite(result, saveSlot, slotLabel(saveSlot))
                                onBack()
                            } else {
                                viewModel.recordSelection(result)
                                onResultTap(result)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = OnSurfaceDim,
            modifier = Modifier.size(18.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = "Search places",
                    color = OnSurface.copy(alpha = 0.35f),
                    fontSize = 15.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = OnSurface,
                    fontSize = 15.sp,
                ),
                cursorBrush = SolidColor(GoldAccent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ResultRow(result: SearchResult, saveMode: Boolean, recent: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (recent) Icons.Rounded.History else Icons.Rounded.Place,
            contentDescription = null,
            tint = if (recent) OnSurfaceDim else GoldAccent,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = result.name,
                color = OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            if (result.address.isNotBlank()) {
                Text(
                    text = result.address,
                    color = OnSurfaceDim,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
        }
        if (saveMode) {
            Text(
                text = "Save",
                color = GoldAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun slotLabel(slot: FavoriteSlot): String = slot.presetName()
