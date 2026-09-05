package com.vlad.ducknetview.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vlad.ducknetview.ui.theme.DuckColors

/**
 * Hand-drawn charts (no chart library). All three are **zero-anchored**: the
 * baseline is always 0 and the series is scaled to its own maximum, so a
 * constant series reads as a flat line instead of the noise a min/max-fitted
 * axis would invent.
 *
 * Test tags: [Sparkline] = "sparkline", [DualSparkline] = "sparkline:dual",
 * [BarChart] = "barchart". A screen may hold several of each, so tests should
 * use onAllNodesWithTag. The default tag is applied before the caller's
 * modifier, so passing your own testTag replaces it.
 */
private const val TAG_SPARKLINE = "sparkline"
private const val TAG_DUAL = "sparkline:dual"
private const val TAG_BARS = "barchart"

@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = DuckColors.Rx,
    height: Dp = 32.dp,
    fill: Boolean = true,
) {
    Canvas(
        Modifier
            .testTag(TAG_SPARKLINE)
            .then(modifier)
            .fillMaxWidth()
            .height(height),
    ) {
        val stroke = 1.5.dp.toPx()
        drawSeries(values, color, fill, scaleOf(values), stroke)
    }
}

/** Rx and tx on one shared zero-anchored scale, so the two are comparable. */
@Composable
fun DualSparkline(
    rx: List<Float>,
    tx: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
) {
    Canvas(
        Modifier
            .testTag(TAG_DUAL)
            .then(modifier)
            .fillMaxWidth()
            .height(height),
    ) {
        val stroke = 1.5.dp.toPx()
        val scale = maxOf(scaleOf(rx), scaleOf(tx))
        drawSeries(rx, DuckColors.Rx, true, scale, stroke)
        drawSeries(tx, DuckColors.Tx, false, scale, stroke)
    }
}

@Composable
fun BarChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    color: Color = DuckColors.Warn,
) {
    Canvas(
        Modifier
            .testTag(TAG_BARS)
            .then(modifier)
            .fillMaxWidth()
            .height(height),
    ) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        drawLine(
            color = color.copy(alpha = 0.3f),
            start = Offset(0f, h - 0.5f),
            end = Offset(w, h - 0.5f),
            strokeWidth = 1f,
        )
        if (values.isEmpty()) return@Canvas
        val scale = scaleOf(values)
        val slot = w / values.size
        val gap = (slot * 0.2f).coerceAtMost(2.dp.toPx())
        val barWidth = (slot - gap).coerceAtLeast(1f)
        values.forEachIndexed { i, v ->
            val barHeight = ((v.coerceAtLeast(0f) / scale) * h).coerceIn(0f, h)
            if (barHeight <= 0f) return@forEachIndexed
            drawRect(
                color = color,
                topLeft = Offset(i * slot, h - barHeight),
                size = Size(barWidth, barHeight),
            )
        }
    }
}

/** Never returns 0, so an all-zero (or empty) series cannot divide by zero. */
private fun scaleOf(values: List<Float>): Float {
    val max = values.maxOrNull() ?: 0f
    return if (max > 0f) max else 1f
}

private fun DrawScope.drawSeries(
    values: List<Float>,
    color: Color,
    fill: Boolean,
    scale: Float,
    stroke: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val inset = stroke / 2f
    val usable = (h - stroke).coerceAtLeast(1f)
    val baseline = h - inset

    if (values.isEmpty()) {
        drawLine(color.copy(alpha = 0.3f), Offset(0f, baseline), Offset(w, baseline), stroke)
        return
    }

    fun yFor(v: Float): Float =
        inset + usable * (1f - (v.coerceAtLeast(0f) / scale).coerceIn(0f, 1f))

    if (values.size == 1) {
        val y = yFor(values[0])
        if (fill) {
            drawRect(
                color = color.copy(alpha = 0.18f),
                topLeft = Offset(0f, y),
                size = Size(w, (baseline - y).coerceAtLeast(0f)),
            )
        }
        drawLine(color, Offset(0f, y), Offset(w, y), stroke)
        return
    }

    val step = w / (values.size - 1)
    val line = Path()
    values.forEachIndexed { i, v ->
        val x = i * step
        val y = yFor(v)
        if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
    }
    if (fill) {
        val area = Path()
        area.addPath(line)
        area.lineTo(w, baseline)
        area.lineTo(0f, baseline)
        area.close()
        drawPath(area, color.copy(alpha = 0.18f))
    }
    drawPath(line, color, style = Stroke(width = stroke))
}
