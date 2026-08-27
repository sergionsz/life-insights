package dev.sergio.lifeinsights.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlin.math.abs

data class ChartSeries(
    val label: String,
    val colour: Color,
    val points: Map<LocalDate, Double>,
    /** Drawn dashed, for values the app inferred rather than measured. */
    val estimated: Boolean = false,
)

/**
 * A deliberately plain line chart.
 *
 * The one rule it takes seriously: a gap in the data is drawn as a gap. Joining across missing days
 * would draw a confident straight line through days the user never logged, which is the chart
 * equivalent of making data up.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    from: LocalDate,
    to: LocalDate,
    modifier: Modifier = Modifier,
    yRange: ClosedFloatingPointRange<Double>? = null,
    height: androidx.compose.ui.unit.Dp = 180.dp,
) {
    val populated = series.filter { it.points.isNotEmpty() }
    if (populated.isEmpty()) {
        Text(
            "No data in this range yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 24.dp),
        )
        return
    }

    val allValues = populated.flatMap { it.points.values }
    val minY = yRange?.start ?: allValues.min()
    val maxY = yRange?.endInclusive ?: allValues.max()
    val spanY = (maxY - minY).takeIf { abs(it) > 1e-9 } ?: 1.0
    val totalDays = (to.toEpochDay() - from.toEpochDay()).coerceAtLeast(1)

    val gridColour = MaterialTheme.colorScheme.outlineVariant

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width
            val h = size.height

            // Baseline and midline, so the eye has something to judge against.
            for (fraction in listOf(0f, 0.5f, 1f)) {
                val y = h * fraction
                drawLine(gridColour, androidx.compose.ui.geometry.Offset(0f, y),
                    androidx.compose.ui.geometry.Offset(w, y), strokeWidth = 1f)
            }

            for (s in populated) {
                val path = Path()
                var penDown = false
                var previousDay: Long? = null

                for ((date, value) in s.points.toSortedMap()) {
                    if (date < from || date > to) continue
                    val dayIndex = date.toEpochDay() - from.toEpochDay()
                    val x = w * (dayIndex.toFloat() / totalDays)
                    val y = h - (h * ((value - minY) / spanY)).toFloat()

                    // Break the line whenever a day is missing.
                    val contiguous = previousDay != null && date.toEpochDay() - previousDay == 1L
                    if (!penDown || !contiguous) path.moveTo(x, y) else path.lineTo(x, y)
                    penDown = true
                    previousDay = date.toEpochDay()
                }

                drawPath(
                    path = path,
                    color = s.colour,
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        pathEffect = if (s.estimated) {
                            PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                        } else {
                            null
                        },
                    ),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            populated.forEach { s ->
                Spacer(Modifier.size(4.dp))
                Spacer(Modifier.size(10.dp).clip(CircleShape).background(s.colour))
                Spacer(Modifier.size(6.dp))
                Text(
                    if (s.estimated) "${s.label} (estimated)" else s.label,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.size(10.dp))
            }
        }
    }
}
