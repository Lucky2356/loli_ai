package ai.loli.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.loli.app.ui.theme.Cyan
import ai.loli.app.ui.theme.Rose
import ai.loli.app.ui.theme.Violet

enum class OrbMode { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

/** Большой индикатор состояния ассистента: «дышит» в ожидании, реагирует на голос, вращается при обдумывании. */
@Composable
fun AssistantOrb(mode: OrbMode, level: Float, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val transition = rememberInfiniteTransition(label = "orb")
    val breath by transition.animateFloat(
        initialValue = 0.94f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(if (mode == OrbMode.IDLE) 2600 else 900), RepeatMode.Reverse), label = "breath",
    )
    val rotation by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (mode == OrbMode.THINKING) 1400 else 9000, easing = LinearEasing)), label = "rotation",
    )
    val voice by animateFloatAsState(if (mode == OrbMode.LISTENING) level else 0f, label = "level")
    val (c1, c2) = when (mode) {
        OrbMode.IDLE -> Violet to Cyan
        OrbMode.LISTENING -> Cyan to Violet
        OrbMode.THINKING -> Violet to Rose
        OrbMode.SPEAKING -> Rose to Violet
        OrbMode.ERROR -> Color(0xFFFF6B6B) to Color(0xFF8B1E3F)
    }
    Canvas(modifier.size(size)) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val base = this.size.minDimension / 2 * 0.62f
        val r = base * (breath + voice * 0.25f)
        // Внешнее свечение
        drawCircle(Brush.radialGradient(listOf(c1.copy(alpha = 0.35f), Color.Transparent), center, r * 1.6f), r * 1.6f, center)
        // Ядро
        drawCircle(Brush.linearGradient(listOf(c1, c2), center - Offset(r, r), center + Offset(r, r)), r, center)
        // Вращающиеся кольца
        for (i in 0 until 3) {
            val angle = Math.toRadians((rotation + i * 120).toDouble())
            val ringR = r * (1.12f + i * 0.09f)
            drawArc(
                color = c2.copy(alpha = 0.55f - i * 0.12f),
                startAngle = (rotation + i * 120) % 360, sweepAngle = 70f + voice * 60f, useCenter = false,
                topLeft = center - Offset(ringR, ringR),
                size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2),
                style = Stroke(width = 3.dp.toPx()),
            )
            drawCircle(c1.copy(alpha = 0.8f), 3.dp.toPx(), center + Offset((ringR * kotlin.math.cos(angle)).toFloat(), (ringR * kotlin.math.sin(angle)).toFloat()))
        }
    }
}
