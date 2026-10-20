package com.example.viavox.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viavox.model.NavigationMath
import com.example.viavox.service.SafetyAlertEngine
import kotlin.math.roundToInt

/**
 * Floating digital speedometer for in-trip and turn-by-turn navigation.
 * Displays real-time GPS speed in km/h, road speed limit (Placa R-19),
 * and triggers a distinctive pulsing visual alert whenever speed exceeds the limit.
 */
@Composable
fun FloatingSpeedometer(
    speedKmh: Float,
    speedLimitKmh: Int?,
    onSpeedLimitChanged: ((Int?) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val currentSpeed = (if (speedKmh < 0.8f) 0f else speedKmh).roundToInt()
    val isOverSpeed = speedLimitKmh != null && currentSpeed > speedLimitKmh
    var menuExpanded by remember { mutableStateOf(false) }

    // Pulsing animation when exceeding speed limit
    val infiniteTransition = rememberInfiniteTransition(label = "overspeed_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(450),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isOverSpeed) Color(0xF2B91C1C) else Color(0xF2081A30),
        animationSpec = tween(300),
        label = "bg_color"
    )

    val borderColor = if (isOverSpeed) {
        Color(0xFFFF3B30).copy(alpha = pulseAlpha)
    } else {
        Color.White.copy(alpha = 0.16f)
    }

    Box(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = backgroundColor,
            border = BorderStroke(if (isOverSpeed) 2.5.dp else 1.2.dp, borderColor),
            shadowElevation = if (isOverSpeed) 12.dp else 6.dp,
            modifier = Modifier
                .testTag("floating_speedometer")
                .clickable {
                    if (onSpeedLimitChanged != null) {
                        menuExpanded = true
                    }
                }
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Digital Speed Display
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = currentSpeed.toString(),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            lineHeight = 32.sp,
                            modifier = Modifier.testTag("speedometer_current_speed")
                        )
                        Text(
                            text = "km/h",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isOverSpeed) Color(0xFFFFD1D1) else Color(0xFFBDCCDA),
                            letterSpacing = 0.4.sp
                        )
                    }

                    // Speed limit sign (Placa R-19 standard)
                    if (speedLimitKmh != null) {
                        SpeedLimitSignBadge(
                            limit = speedLimitKmh,
                            isExceeded = isOverSpeed,
                            modifier = Modifier.testTag("speedometer_speed_limit")
                        )
                    }
                }

                // Overspeed alert badge
                AnimatedVisibility(
                    visible = isOverSpeed,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val overAmount = currentSpeed - (speedLimitKmh ?: 0)
                    Row(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.22f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                            .testTag("speedometer_overspeed_badge"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Alerta de excesso de velocidade",
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "+$overAmount km/h",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Speed limit selection dropdown
        if (onSpeedLimitChanged != null) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                Text(
                    text = "Definir Limite da Via",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    color = Color.Gray
                )
                DropdownMenuItem(
                    text = { Text("Auto (Detectar)") },
                    onClick = {
                        onSpeedLimitChanged(null)
                        menuExpanded = false
                    }
                )
                listOf(30, 40, 50, 60, 70, 80, 90, 100).forEach { limit ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (speedLimitKmh == limit) "✓ $limit km/h" else "$limit km/h"
                            )
                        },
                        onClick = {
                            onSpeedLimitChanged(limit)
                            menuExpanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * Standard Brazilian R-19 Speed Limit circular badge (white disc, red border, black text).
 */
@Composable
fun SpeedLimitSignBadge(
    limit: Int,
    isExceeded: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(
                BorderStroke(3.5.dp, Color(0xFFE53935)),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = limit.toString(),
            color = Color(0xFF1B1B1B),
            fontSize = if (limit >= 100) 13.sp else 16.sp,
            fontWeight = FontWeight.Black,
            lineHeight = 16.sp
        )
    }
}

/**
 * Resolves the speed limit for the current location.
 * Priority:
 * 1. Nearest safety camera/radar or speed hump ahead or nearby (within 1000m) with an explicit speed limit.
 * 2. Road classification heuristics based on street name (BR/AM highway = 80, Avenue = 60, local street = 40).
 * 3. Default urban limit (60 km/h).
 */
fun resolveRoadSpeedLimit(
    road: String?,
    safetyFeatures: List<SafetyAlertEngine.SafetyFeature>,
    userLat: Double,
    userLon: Double
): Int {
    // 1. Check nearby safety camera / speed hump within 1000m
    val nearestCameraWithLimit = safetyFeatures
        .filter { it.speedLimitKmh != null && it.speedLimitKmh > 0 }
        .minByOrNull { feat ->
            NavigationMath.meters(userLat, userLon, feat.latitude, feat.longitude)
        }

    if (nearestCameraWithLimit != null) {
        val dist = NavigationMath.meters(userLat, userLon, nearestCameraWithLimit.latitude, nearestCameraWithLimit.longitude)
        if (dist <= 1000.0) {
            return nearestCameraWithLimit.speedLimitKmh!!
        }
    }

    // 2. Road heuristics based on Brazilian road taxonomy
    val cleanRoad = road?.trim().orEmpty().lowercase()
    return when {
        cleanRoad.startsWith("br-") || cleanRoad.startsWith("am-") || cleanRoad.contains("rodovia") || cleanRoad.contains("via expressa") -> 80
        cleanRoad.startsWith("av.") || cleanRoad.startsWith("avenida") || cleanRoad.contains("boulevard") -> 60
        cleanRoad.startsWith("rua") || cleanRoad.startsWith("r.") || cleanRoad.startsWith("travessa") || cleanRoad.startsWith("tv.") || cleanRoad.startsWith("alameda") || cleanRoad.startsWith("beco") -> 40
        else -> 60
    }
}
