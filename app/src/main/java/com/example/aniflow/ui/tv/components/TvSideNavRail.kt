package com.example.aniflow.ui.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.aniflow.theme.*

@Composable
fun TvSideNavRail(
    selectedIndex: Int,
    items: List<Pair<ImageVector, String>>,
    onSelect: (Int) -> Unit
) {
    val focusRequesters = remember { List(items.size) { FocusRequester() } }

    Column(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(PrimaryDarker)
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        items.forEachIndexed { index, pair ->
            TvNavRailItem(
                icon = pair.first,
                isSelected = selectedIndex == index,
                focusRequester = focusRequesters[index],
                onSelect = { onSelect(index) }
            )
        }
    }

    // Auto-focus the current index item on start
    LaunchedEffect(Unit) {
        if (selectedIndex in items.indices) {
            focusRequesters[selectedIndex].requestFocus()
        }
    }
}

@Composable
fun TvNavRailItem(
    icon: ImageVector,
    isSelected: Boolean,
    focusRequester: FocusRequester,
    onSelect: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(54.dp)
            .focusRequester(focusRequester)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    isSelected -> PrimaryAccent
                    isFocused -> SurfaceBorder
                    else -> Color.Transparent
                }
            )
            .clickable { onSelect() }
            .onFocusChanged { isFocused = it.isFocused },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected || isFocused) TextPrimary else TextSecondary,
            modifier = Modifier.size(24.dp)
        )
    }
}
