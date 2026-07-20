package com.recon.dash.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import com.recon.dash.search.SearchResult
import com.recon.dash.ui.theme.DarkBackground
import com.recon.dash.ui.theme.DarkSurface
import com.recon.dash.ui.theme.GoldAccent
import com.recon.dash.ui.theme.OnSurface

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

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Back",
                color = GoldAccent,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(end = 16.dp, top = 8.dp, bottom = 8.dp),
            )
            SearchField(
                value = query,
                onValueChange = { viewModel.updateQuery(it) },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
        }

        Spacer(Modifier.height(16.dp))

        if (error != null) {
            Text(
                text = error ?: "",
                color = OnSurface.copy(alpha = 0.5f),
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(results, key = { "${it.location.lat},${it.location.lng}" }) { result ->
                ResultRow(result = result, onClick = { onResultTap(result) })
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
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurface)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
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

@Composable
private fun ResultRow(result: SearchResult, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = result.name,
            color = OnSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
        if (result.address.isNotBlank()) {
            Text(
                text = result.address,
                color = OnSurface.copy(alpha = 0.5f),
                fontSize = 13.sp,
                maxLines = 1,
            )
        }
    }
}
