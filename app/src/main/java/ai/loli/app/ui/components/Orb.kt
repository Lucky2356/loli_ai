package ai.loli.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ai.loli.app.ui.theme.Coral

enum class OrbMode { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

/**
 * Минималистичный индикатор ассистента: мягкая светящаяся сфера.
 * Ожидание — медленное «дыхание», слушает — волны от громкости голоса, думает — бегущий блик, говорит — пульс.
 */
@Composable
fun AssistantOrb(mode: OrbMode, level: Float, modifier: Modifier = Modifier, size: Dp = 180.dp) {
    val primary = ai.loli.app.ui.theme.LocalOrbColor.current ?: MaterialTheme.colorScheme.primary
    // Второй цвет сферы — из выбранного цвета Лоли.
    val second = MaterialTheme.colorScheme.tertiary
    val transition = rememberInfiniteTransition(label = "orb")
    val breath by transition.animateFloat(
        0f, 1f, infiniteRepeatable(tween(if (mode == OrbMode.SPEAKING) 700 else 2800), RepeatMode.Reverse), label = "breath",
    )
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "spin")
    val wave by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "wave")
    val voice by animateFloatAsState(if (mode == OrbMode.LISTENING) level else 0f, spring(stiffness = 300f), label = "voice")
    val core by animateColorAsState(
        when (mode) {
            OrbMode.IDLE -> primary
            OrbMode.LISTENING -> second
            OrbMode.THINKING -> primary
            OrbMode.SPEAKING -> primary
            OrbMode.ERROR -> Coral
        }, tween(400), label = "core",
    )
    val glow by animateColorAsState(if (mode == OrbMode.LISTENING) primary else second, tween(400), label = "glow")

    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val base = this.size.minDimension / 2 * 0.46f
        val pulse = when (mode) {
            OrbMode.IDLE -> 0.03f * breath
            OrbMode.SPEAKING -> 0.07f * breath
            else -> 0.02f * breath
        }
        val r = base * (1f + pulse + voice * 0.22f)

        // Волны при прослушивании: расходящиеся тонкие кольца.
        if (mode == OrbMode.LISTENING) {
            for (i in 0 until 3) {
                val t = (wave + i / 3f) % 1f
                val rr = r * (1.05f + t * (0.7f + voice * 0.4f))
                drawCircle(core.copy(alpha = (1f - t) * 0.35f), rr, c, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
        // Мягкое свечение.
        drawCircle(Brush.radialGradient(listOf(core.copy(alpha = 0.28f), Color.Transparent), c, r * 1.9f), r * 1.9f, c)
        // Сфера: градиент от акцента к второму цвету.
        drawCircle(Brush.linearGradient(listOf(core, glow.copy(alpha = 0.85f)), c - Offset(r, r), c + Offset(r, r)), r, c)
        // Блик сверху-слева — объём.
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), c - Offset(r * 0.35f, r * 0.4f), r * 0.8f),
            r, c,
        )
        // Думает: бегущая дуга.
        if (mode == OrbMode.THINKING) {
            rotate(spin, c) {
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.9f)), c),
                    startAngle = 0f, sweepAngle = 300f, useCenter = false,
                    topLeft = c - Offset(r * 1.18f, r * 1.18f),
                    size = androidx.compose.ui.geometry.Size(r * 2.36f, r * 2.36f),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}
