package ai.loli.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import ai.loli.app.AppContainer
import ai.loli.app.ui.components.groupColor
import ai.loli.core.health.HabitEntry
import ai.loli.core.health.Vitals
import java.time.Instant

/** Здоровье за 30 дней: давление (верхнее и нижнее), вес, сон — простые графики и последние значения. */
@Composable
fun HealthPane(c: AppContainer, name: String) {
    var bp by remember { mutableStateOf<List<HabitEntry>>(emptyList()) }
    var weight by remember { mutableStateOf<List<HabitEntry>>(emptyList()) }
    var sleep by remember { mutableStateOf<List<HabitEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        val from = Instant.now().minusSeconds(30L * 24 * 3600)
        bp = c.store.habits.since(from, Vitals.BP)
        weight = c.store.habits.since(from, Vitals.WEIGHT)
        sleep = c.store.habits.since(from, Vitals.SLEEP)
    }
    Column(Modifier.padding(top = 4.dp)) {
        if (bp.isEmpty() && weight.isEmpty() && sleep.isEmpty()) {
            Text(
                "Здесь появятся графики. Скажите: «$name, давление 120 на 80», «вес 68», «спала 7 часов».",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            return
        }
        if (bp.isNotEmpty()) {
            val dia = bp.map { e -> Regex("""^\d+/(\d+)""").find(e.name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0 }
            ChartCard(
                "Давление", bp.last().name.substringBefore(',').replace("/", " на "),
                listOf(bp.map { it.at to it.amount }, bp.mapIndexed { i, e -> e.at to dia[i] }),
            )
        }
        if (weight.isNotEmpty()) ChartCard("Вес", "${fmt(weight.last().amount)} кг", listOf(weight.map { it.at to it.amount }))
        if (sleep.isNotEmpty()) ChartCard("Сон", "${fmt(sleep.last().amount)} ч · в среднем ${fmt(Math.round(sleep.map { it.amount }.average() * 10) / 10.0)} ч", listOf(sleep.map { it.at to it.amount }))
        Text(
            "За последние 30 дней. Спросите голосом: «какое у меня давление за неделю», «мой вес за месяц».",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

private fun fmt(x: Double): String = if (x % 1.0 == 0.0) x.toLong().toString() else String.format(java.util.Locale("ru"), "%.1f", x)

@Composable
private fun ChartCard(title: String, value: String, series: List<List<Pair<Instant, Double>>>) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Surface(shape = MaterialTheme.shapes.large, color = groupColor(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(12.dp))
                Text(value, style = MaterialTheme.typography.titleMedium, color = primary)
            }
            val points = series.flatten()
            if (points.size >= 2) {
                val minX = points.minOf { it.first.toEpochMilli() }.toFloat()
                val maxX = points.maxOf { it.first.toEpochMilli() }.toFloat().let { if (it == minX) minX + 1 else it }
                val minY = points.minOf { it.second }.toFloat().let { it - maxOf(1f, it * 0.02f) }
                val maxY = points.maxOf { it.second }.toFloat().let { it + maxOf(1f, it * 0.02f) }
                Canvas(Modifier.fillMaxWidth().height(120.dp).padding(top = 12.dp)) {
                    fun at(p: Pair<Instant, Double>) = Offset(
                        (p.first.toEpochMilli() - minX) / (maxX - minX) * size.width,
                        size.height - (p.second.toFloat() - minY) / (maxY - minY) * size.height,
                    )
                    for (i in 0..2) {
                        val y = size.height * i / 2f
                        drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                    series.forEachIndexed { k, line ->
                        val color: Color = if (k == 0) primary else secondary
                        val path = Path()
                        line.forEachIndexed { i, p -> val o = at(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
                        drawPath(path, color, style = Stroke(width = 4f))
                        line.forEach { drawCircle(color, radius = 5f, center = at(it)) }
                    }
                }
            } else {
                Text("Нужно хотя бы два замера, чтобы нарисовать график.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}
