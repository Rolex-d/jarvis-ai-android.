package com.jarvis.ai

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class JarvisState { IDLE, LISTENING, THINKING, SPEAKING }

@Composable
fun ArcReactor(
    state: JarvisState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "reactor")

    val outerRotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    JarvisState.LISTENING -> 2200
                    JarvisState.THINKING -> 1400
                    JarvisState.SPEAKING -> 3000
                    JarvisState.IDLE -> 12000
                },
                easing = LinearEasing
            )
        ),
        label = "outerRot"
    )

    val innerRotation by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (state == JarvisState.IDLE) 9000 else 1800,
                easing = LinearEasing
            )
        ),
        label = "innerRot"
    )

    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = if (state == JarvisState.IDLE) 1.0f else 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (state == JarvisState.LISTENING) 450 else 1100
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    val primaryColor = when (state) {
        JarvisState.LISTENING -> Color(0xFF00F0FF)
        JarvisState.THINKING -> Color(0xFFFFB300)
        JarvisState.SPEAKING -> Color(0xFF38BDF8)
        JarvisState.IDLE -> Color(0xFF00D9FF)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(240.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        Canvas(modifier = Modifier.size(230.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f

            // Ambient radial glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.35f * pulse),
                        primaryColor.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = radius * pulse
                ),
                radius = radius * pulse,
                center = center
            )

            // Outer segmented HUD ring
            rotate(outerRotation, pivot = center) {
                val rOuter = radius * 0.86f
                for (i in 0 until 12) {
                    drawArc(
                        color = primaryColor.copy(alpha = if (i % 2 == 0) 0.9f else 0.35f),
                        startAngle = i * 30f + 4f,
                        sweepAngle = 22f,
                        useCenter = false,
                        topLeft = Offset(center.x - rOuter, center.y - rOuter),
                        size = Size(rOuter * 2, rOuter * 2),
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Butt)
                    )
                }
            }

            // Middle static containment ring + 10 copper-style reactor coils
            val rMid = radius * 0.68f
            drawCircle(
                color = primaryColor.copy(alpha = 0.45f),
                radius = rMid,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
            for (i in 0 until 10) {
                val angleRad = (i * 36.0) * PI / 180.0
                val start = Offset(
                    center.x + (rMid * 0.85f * cos(angleRad)).toFloat(),
                    center.y + (rMid * 0.85f * sin(angleRad)).toFloat()
                )
                val end = Offset(
                    center.x + (rMid * 1.12f * cos(angleRad)).toFloat(),
                    center.y + (rMid * 1.12f * sin(angleRad)).toFloat()
                )
                drawLine(
                    color = primaryColor.copy(alpha = 0.85f),
                    start = start,
                    end = end,
                    strokeWidth = 5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Counter-rotating inner ring
            rotate(innerRotation, pivot = center) {
                val rInner = radius * 0.46f
                for (i in 0 until 3) {
                    drawArc(
                        color = Color(0xFFE0FBFF),
                        startAngle = i * 120f + 12f,
                        sweepAngle = 92f,
                        useCenter = false,
                        topLeft = Offset(center.x - rInner, center.y - rInner),
                        size = Size(rInner * 2, rInner * 2),
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // Glowing Arc Reactor core
            val coreRadius = radius * 0.28f * (0.92f + (pulse - 0.88f) * 0.6f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, primaryColor, Color.Transparent),
                    center = center,
                    radius = coreRadius * 1.4f
                ),
                radius = coreRadius * 1.4f,
                center = center
            )
            drawCircle(
                color = Color(0xFFE8FDFF),
                radius = coreRadius * 0.65f,
                center = center
            )
        }
    }
}
