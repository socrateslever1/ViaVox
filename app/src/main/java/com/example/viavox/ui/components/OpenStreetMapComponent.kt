package com.example.viavox.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viavox.ui.theme.ViaVoxDarkBg
import com.example.viavox.ui.theme.ViaVoxTeal
import com.example.viavox.ui.theme.ViaVoxTealGlow
import java.util.Locale

@Composable
fun OpenStreetMapComponent(
    latitude: Double,
    longitude: Double,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    var zoomLevel by remember { mutableFloatStateOf(16f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    // Pulsing circle animation around the pin
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B1726))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    zoomLevel = (zoomLevel * zoom).coerceIn(12f, 19f)
                    panOffsetX += pan.x
                    panOffsetY += pan.y
                }
            }
            .testTag("map_view")
    ) {
        // Decorative map roads and grid canvas simulating OpenStreetMap dark tiles
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val centerX = w / 2f + panOffsetX
            val centerY = h / 2f + panOffsetY

            // Base terrain background
            drawRect(color = Color(0xFF0E1A29))

            // Draw stylized road grid
            val gridStep = 80f * (zoomLevel / 16f)
            val roadColor = Color(0xFF1B2C42)
            val majorRoadColor = Color(0xFF233B57)

            var x = (centerX % gridStep)
            while (x < w) {
                drawLine(
                    color = roadColor,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 3f
                )
                x += gridStep
            }

            var y = (centerY % gridStep)
            while (y < h) {
                drawLine(
                    color = roadColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 3f
                )
                y += gridStep
            }

            // Diagonal major avenue
            val avenuePath = Path().apply {
                moveTo(centerX - 400f, centerY + 300f)
                lineTo(centerX + 400f, centerY - 300f)
            }
            drawPath(
                path = avenuePath,
                color = majorRoadColor,
                style = Stroke(width = 9f)
            )

            // Cross avenue
            val crossPath = Path().apply {
                moveTo(centerX - 300f, centerY - 350f)
                quadraticTo(centerX, centerY, centerX + 350f, centerY + 350f)
            }
            drawPath(
                path = crossPath,
                color = majorRoadColor,
                style = Stroke(width = 8f)
            )

            // Draw user GPS location pin with pulse effect
            if (isActive) {
                drawCircle(
                    color = ViaVoxTealGlow.copy(alpha = pulseAlpha),
                    radius = 22f * pulseScale,
                    center = Offset(centerX, centerY)
                )
            }

            // Outer shadow / aura
            drawCircle(
                color = ViaVoxTealGlow.copy(alpha = 0.35f),
                radius = 16f,
                center = Offset(centerX, centerY)
            )

            // White border ring
            drawCircle(
                color = Color.White,
                radius = 11f,
                center = Offset(centerX, centerY)
            )

            // Solid inner teal dot
            drawCircle(
                color = ViaVoxTealGlow,
                radius = 8f,
                center = Offset(centerX, centerY)
            )
        }

        // Coordinates badge overlay
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ViaVoxDarkBg.copy(alpha = 0.85f))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = String.format(Locale.US, "%.4f, %.4f", latitude, longitude),
                color = ViaVoxTeal,
                fontSize = 11.sp
            )
        }

        // OpenStreetMap attribution badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(
                text = "© OpenStreetMap contributors",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 9.sp
            )
        }
    }
}
