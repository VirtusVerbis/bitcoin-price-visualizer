package com.example.myapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PriceDisplay(
    label: String,
    price: Double?,
    isConnected: Boolean,
    previousPrice: Double?,
    buyVolume: Double?,
    sellVolume: Double?,
    maxVolume: Double,
    volumeAnimating: Boolean,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    pulseDirection: PulseDirection = PulseDirection.LEFT_TO_RIGHT
) {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val priceFontSize = (screenHeightDp / 20 * 0.4).sp  // 60% smaller (40% of original)
    val labelFontSize = (priceFontSize.value * 0.6 * 1.25).sp  // 25% larger (75% of price instead of 60%)
    val density = LocalDensity.current
    // Convert sp to dp: sp value * 0.1, then use the value as dp
    val barHeight = (priceFontSize.value * 0.1f).dp // 10% of price height
    
    var priceTextWidth by remember { mutableStateOf(0.dp) }
    
    val priceText = if (price != null) {
        String.format("%.2f", price)
    } else {
        "Unavailable"
    }
    
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = horizontalAlignment
    ) {
        // Label with WiFi indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                fontSize = labelFontSize,
                color = androidx.compose.ui.graphics.Color.White
            )
            Icon(
                imageVector = if (isConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                contentDescription = if (isConnected) "Connected" else "Disconnected",
                modifier = Modifier.size((labelFontSize.value * 0.8).dp),
                tint = if (isConnected) Color(0xFF4CAF50) else Color(0xFF757575)
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Price display - measure width
        Text(
            text = priceText,
            fontSize = priceFontSize,
            color = when {
                price == null -> Color(0xFF757575)
                previousPrice == null -> androidx.compose.ui.graphics.Color.White
                price > previousPrice -> Color(0xFF4CAF50) // Green
                price < previousPrice -> Color(0xFFF44336) // Red
                else -> androidx.compose.ui.graphics.Color.White
            },
            modifier = Modifier.onGloballyPositioned { coordinates ->
                priceTextWidth = with(density) { coordinates.size.width.toDp() }
            }
        )
        
        // Volume bars
        Spacer(modifier = Modifier.height(4.dp))
        
        // BUY volume bar (green, top)
        VolumeBar(
            volume = buyVolume ?: 0.0,
            maxVolume = maxVolume,
            color = Color(0xFF4CAF50), // Green
            direction = pulseDirection,
            isAnimating = volumeAnimating,
            barHeight = barHeight,
            barWidth = priceTextWidth,
            modifier = Modifier
        )
        
        Spacer(modifier = Modifier.height(2.dp))
        
        // SELL volume bar (red, bottom)
        VolumeBar(
            volume = sellVolume ?: 0.0,
            maxVolume = maxVolume,
            color = Color(0xFFF44336), // Red
            direction = pulseDirection,
            isAnimating = volumeAnimating,
            barHeight = barHeight,
            barWidth = priceTextWidth,
            modifier = Modifier
        )
    }
}
