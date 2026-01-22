package com.example.myapp.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class PulseDirection {
    LEFT_TO_RIGHT,  // Bars pulse from left towards center (for Binance)
    RIGHT_TO_LEFT   // Bars pulse from right towards center (for Coinbase)
}

@Composable
fun VolumeBar(
    volume: Double,
    maxVolume: Double,
    color: Color,
    direction: PulseDirection,
    isAnimating: Boolean,
    modifier: Modifier = Modifier,
    barHeight: Dp = 4.dp,
    barWidth: Dp
) {
    val progress = if (maxVolume > 0) {
        (volume / maxVolume).coerceIn(0.0, 1.0).toFloat()
    } else {
        0f
    }
    
    // Trigger pulse animation when isAnimating is true
    var pulseScale by remember { mutableStateOf(1f) }
    
    LaunchedEffect(isAnimating) {
        if (isAnimating) {
            // Animate to larger scale (pulse effect)
            pulseScale = 1.2f
            kotlinx.coroutines.delay(300)
            // Return to normal
            pulseScale = 1f
        }
    }
    
    // Calculate the target progress with pulse effect, clamped to 1.0
    val targetProgress = (progress * pulseScale).coerceIn(0f, 1f)
    
    // Animate the width - animate towards target progress
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 300),
        label = "volumeBarAnimation"
    )
    
    Box(
        modifier = modifier
            .width(barWidth)
            .height(barHeight),
        contentAlignment = when (direction) {
            PulseDirection.LEFT_TO_RIGHT -> Alignment.CenterStart
            PulseDirection.RIGHT_TO_LEFT -> Alignment.CenterEnd
        }
    ) {
        // Background track
        Box(
            modifier = Modifier
                .width(barWidth)
                .height(barHeight)
                .background(Color.Gray.copy(alpha = 0.3f))
        )
        
        // Filled bar - positioned based on direction
        Box(
            modifier = Modifier
                .width(barWidth * animatedProgress)
                .fillMaxHeight()
                .background(color)
                .align(when (direction) {
                    PulseDirection.LEFT_TO_RIGHT -> Alignment.CenterStart
                    PulseDirection.RIGHT_TO_LEFT -> Alignment.CenterEnd
                })
        )
    }
}
