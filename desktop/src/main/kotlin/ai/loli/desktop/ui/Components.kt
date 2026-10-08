package ai.loli.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Значок Лоли для окна и трея: градиентный круг с «Л». */
val LoliIcon: ImageVector = ImageVector.Builder("loli", 32.dp, 32.dp, 32f, 32f).apply {
    path(fill = Brush.linearGradient(listOf(Color(0xFF7C7BFF), Color(0xFFB46BFF)), Offset(0f, 0f), Offset(32f, 32f))) {
        moveTo(16f, 1f); arcTo(15f, 15f, 0f, true, true, 15.99f, 1f); close()
    }
    path(fill = SolidColor(Color.White)) {
        moveTo(9f, 23f); lineTo(14.5f, 8f); lineTo(17.5f, 8f); lineTo(23f, 23f); lineTo(20f, 23f); lineTo(16f, 11.5f); lineTo(12f, 23f); close()
    }
}.build()

/**
 * Сфера Лоли: мягкий градиент со свечением. [active] — слушает или говорит (дышит), [level] — громкость голоса.
 */
@Composable
fun Orb(size: Dp, active: Boolean = false, level: Float = 0f, modifier: Modifier = Modifier) {
    val p = palette
    val t = rememberInfiniteTransition()
    val breath by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse))
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)))
    val boost by animateFloatAsState(if (active) 1f + level * 0.25f else 1f, tween(120))
    Canvas(modifier.size(size).scale(if (active) breath * boost else 1f)) {
        val r = this.size.minDimension / 2
        // Свечение
        drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = if (active) 0.45f else 0.25f), Color.Transparent), center, r * 1.0f), r)
        // Тело сферы
        val angle = Math.toRadians(spin.toDouble())
        val shift = Offset((Math.cos(angle) * r * 0.25).toFloat(), (Math.sin(angle) * r * 0.25).toFloat())
        drawCircle(
            Brush.linearGradient(listOf(p.accent, p.accent2), center - Offset(r, r) + shift, center + Offset(r, r) - shift),
            r * 0.72f,
        )
        // Блик
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), center - Offset(r * 0.25f, r * 0.3f), r * 0.45f), r * 0.6f, center - Offset(r * 0.12f, r * 0.15f))
    }
}

/** Карточка: поверхность с тонкой рамкой и скруглением 16. */
@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    val p = palette
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(p.surface).border(1.dp, p.outline, RoundedCornerShape(16.dp)).padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, modifier = modifier.padding(bottom = 10.dp))

/** Заголовок страницы с подзаголовком и действиями справа. */
@Composable
fun PageHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium.copy(color = palette.muted), modifier = Modifier.padding(top = 4.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Кнопка с градиентом (главное действие). */
@Composable
fun AccentButton(text: String, onClick: () -> Unit, enabled: Boolean = true, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val p = palette
    Row(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (enabled) p.gradient else SolidColor(p.surfaceHover))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (enabled) p.onAccent else p.faint, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(color = if (enabled) p.onAccent else p.faint))
    }
}

/** Вторичная кнопка: прозрачная с рамкой. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, icon: ImageVector? = null, danger: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier) {
    val p = palette
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val color = if (danger) p.danger else p.text
    Row(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (hovered && enabled) p.surfaceHover else Color.Transparent)
            .border(1.dp, p.outline, RoundedCornerShape(12.dp))
            .hoverable(src).clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = if (enabled) color else p.faint, modifier = Modifier.size(17.dp))
        Text(text, style = MaterialTheme.typography.labelLarge.copy(color = if (enabled) color else p.faint))
    }
}

/** Круглая кнопка-значок с подсветкой при наведении. */
@Composable
fun IconCircle(icon: ImageVector, description: String, onClick: () -> Unit, size: Dp = 36.dp, tint: Color? = null, background: Color? = null, enabled: Boolean = true) {
    val p = palette
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(background ?: if (hovered && enabled) p.surfaceHover else Color.Transparent)
            .hoverable(src).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint ?: if (enabled) p.muted else p.faint, modifier = Modifier.size(size * 0.5f)) }
}

/** Поле ввода в стиле Лоли: подпись сверху, мягкий фон, рамка-акцент при фокусе. */
@Composable
fun Field(
    value: String, onChange: (String) -> Unit, label: String, placeholder: String = "", secret: Boolean = false,
    modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null,
) {
    val p = palette
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surfaceHigh).border(1.dp, p.outline, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium.copy(color = p.faint))
                BasicTextField(
                    value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.text),
                    cursorBrush = SolidColor(p.accent), visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
        }
    }
}

/** Строка настройки: название, пояснение, переключатель. */
@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = palette
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = p.accent, checkedThumbColor = Color.White, uncheckedTrackColor = p.surfaceHover,
                uncheckedThumbColor = p.muted, uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** Переключатель вариантов «таблетками». */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val p = palette
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(p.surfaceHigh).border(1.dp, p.outline, RoundedCornerShape(12.dp)).padding(4.dp)) {
        options.forEach { (value, title) ->
            val on = value == selected
            Box(
                Modifier.clip(RoundedCornerShape(9.dp)).background(if (on) p.surface else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, p.outline, RoundedCornerShape(9.dp)) else Modifier)
                    .clickable { onSelect(value) }.padding(horizontal = 14.dp, vertical = 7.dp),
            ) { Text(title, style = MaterialTheme.typography.labelLarge.copy(color = if (on) p.text else p.muted)) }
        }
    }
}

/** Маленькая плашка: «AI», «офлайн», «сегодня». */
@Composable
fun Tag(text: String, color: Color? = null) {
    val p = palette
    val c = color ?: p.muted
    Text(
        text, style = MaterialTheme.typography.labelSmall.copy(color = c),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** Пустой экран: значок, заголовок, подсказка. */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String, modifier: Modifier = Modifier) {
    val p = palette
    Column(modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(p.surfaceHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = p.muted, modifier = Modifier.size(26.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium.copy(color = p.muted), modifier = Modifier.padding(top = 6.dp))
    }
}

/** Тонкая полоса-разделитель. */
@Composable
fun Divider(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(palette.outline))
