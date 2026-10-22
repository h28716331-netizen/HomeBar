package com.homebar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.animation.Interpolator

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueFormatter: (Float) -> String,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(value) }

    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueFormatter(if (dragging) local else value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = (if (dragging) local else value).coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = {
                dragging = true
                local = it
                onChange(it)
            },
            onValueChangeFinished = {
                dragging = false
                onCommit()
            },
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun <T> PickerRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(text = labelOf(selected), maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(labelOf(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 把缓动曲线画出来。用户调「速度曲线」时能直接看到形状，
 * 不用反复开关应用去试手感。
 */
@Composable
fun EasingCurveGraph(interpolator: Interpolator, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)

    val samples = remember(interpolator) {
        val n = 64
        FloatArray(n + 1) { i -> interpolator.getInterpolation(i.toFloat() / n) }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val pad = 6f

        // 网格
        for (i in 0..4) {
            val x = pad + (w - pad * 2) * i / 4f
            drawLine(grid, Offset(x, pad), Offset(x, h - pad), strokeWidth = 1f)
            val y = pad + (h - pad * 2) * i / 4f
            drawLine(grid, Offset(pad, y), Offset(w - pad, y), strokeWidth = 1f)
        }

        // 曲线允许超出 0~1（回弹/弹簧会冲出去），这里按 -0.3~1.3 归一化展示
        val lo = -0.3f
        val hi = 1.3f
        fun toX(i: Int) = pad + (w - pad * 2) * i / (samples.size - 1).toFloat()
        fun toY(v: Float) = h - pad - (h - pad * 2) * ((v - lo) / (hi - lo))

        val path = Path()
        val area = Path()
        samples.forEachIndexed { i, v ->
            val x = toX(i)
            val y = toY(v.coerceIn(lo, hi))
            if (i == 0) {
                path.moveTo(x, y)
                area.moveTo(x, toY(0f))
                area.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                area.lineTo(x, y)
            }
        }
        area.lineTo(toX(samples.size - 1), toY(0f))
        area.close()

        drawPath(area, color = fill)
        drawPath(path, color = line, style = Stroke(width = 3f))

        // 0 与 1 两条基准线
        drawLine(
            Color.Gray.copy(alpha = 0.5f),
            Offset(pad, toY(0f)), Offset(w - pad, toY(0f)),
            strokeWidth = 1.5f,
        )
        drawLine(
            Color.Gray.copy(alpha = 0.5f),
            Offset(pad, toY(1f)), Offset(w - pad, toY(1f)),
            strokeWidth = 1.5f,
        )
    }
}

@Composable
fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}
