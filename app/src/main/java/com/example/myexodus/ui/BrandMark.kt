package com.example.myexodus.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.myexodus.R

/**
 * The logo art is drawn for a white field: its wordmark and the stem are dark green, which the
 * near-black dark surface swallows entirely. Both placements therefore sit the art on a white
 * plate — invisible in light mode (the surface there is already white), an app-icon-style chip
 * at night. The plate stays white in both modes on purpose: it is brand, not palette.
 * Assets come from scripts/GenerateIcons.java.
 */

/** Leaf mark alone, for headers where the app name is already on screen as text. */
@Composable
fun BrandMark(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo_mark),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .background(Color.White, CircleShape)
            .padding(size / 9),
    )
}

/** Full lockup (mark + wordmark), for the one first-run brand moment. */
@Composable
fun BrandLockup(height: Dp = 72.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.logo_lockup),
        contentDescription = null,
        modifier = modifier
            .height(height)
            .background(Color.White, RoundedCornerShape(16.dp))
            .padding(horizontal = height / 5, vertical = height / 8),
    )
}
