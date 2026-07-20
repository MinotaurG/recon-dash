package com.recon.dash.ui.theme

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.navigation.NavBackStackEntry

val SpringSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

val SnapSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessHigh,
)

fun slideInFromRight(): EnterTransition =
    slideInHorizontally(
        initialOffsetX = { it / 3 },
        animationSpec = tween(300, easing = FastOutSlowInEasing),
    ) + fadeIn(animationSpec = tween(200))

fun slideOutToRight(): ExitTransition =
    slideOutHorizontally(
        targetOffsetX = { it / 3 },
        animationSpec = tween(250, easing = FastOutSlowInEasing),
    ) + fadeOut(animationSpec = tween(150))

fun slideInFromBottom(): EnterTransition =
    slideInVertically(
        initialOffsetY = { it / 4 },
        animationSpec = tween(350, easing = FastOutSlowInEasing),
    ) + fadeIn(animationSpec = tween(250))

fun slideOutToBottom(): ExitTransition =
    slideOutVertically(
        targetOffsetY = { it / 4 },
        animationSpec = tween(250, easing = FastOutSlowInEasing),
    ) + fadeOut(animationSpec = tween(150))

fun fadeInSmooth(): EnterTransition =
    fadeIn(animationSpec = tween(250, easing = LinearOutSlowInEasing))

fun fadeOutSmooth(): ExitTransition =
    fadeOut(animationSpec = tween(200, easing = FastOutLinearInEasing))
