package uk.co.tfd.nmeamonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.tfd.nmeamonitor.history.RingSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val AxisColor = Color(0xFF9E9E9E)
private val GridColor = Color(0x33888888)
private val CrosshairColor = Color(0xCCFFFFFF)

/** Y-axis unit group. Series sharing a group share an axis. */
enum class UnitGroup(val label: String) {
    RPM("rpm"),
    TEMP_C("°C"),
    VOLT("V"),
    AMP("A"),
    KNOTS("kn"),
    PERCENT("%"),
    METRE("m"),
}

/**
 * One trace plotted on a [MultiSeriesChart]. The chart is stream-
 * agnostic: each series carries its own [RingSnapshot] (the x-axis is
 * wall-clock time, so per-stream snapshots align via timestamps) and its
 * own value reader.
 */
data class PlottedSeries(
    val label: String,
    val color: Color,
    val unit: UnitGroup,
    val format: String,
    val snapshot: RingSnapshot,
    /** Read value at slot `i` of the carried [snapshot]; null = no data. */
    val read: (Int) -> Double?,
)

/**
 * Renders one chart panel that may carry up to two unit groups (left
 * Y axis and right Y axis). All series in [leftSeries] must share one
 * [UnitGroup]; same for [rightSeries]. The two groups must differ.
 *
 * State that needs synchronising — `tStart` / `tEnd` / `crosshairMs` —
 * is hoisted by the parent. The chart reports its plot bounds via
 * [onPlotLayout] so the parent's gesture handler can convert pointer X
 * to a timestamp.
 */
@Composable
fun MultiSeriesChart(
    leftSeries: List<PlottedSeries>,
    rightSeries: List<PlottedSeries>,
    tStart: Long,
    tEnd: Long,
    crosshairMs: Long?,
    modifier: Modifier = Modifier,
    // Optional second left axis (its own scale), drawn in a column further
    // left of the primary left axis. Empty for the common two-axis case.
    left2Series: List<PlottedSeries> = emptyList(),
    onPlotLayout: (leftPx: Float, widthPx: Float) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current

    val gridSteps = 4
    val leftAxis = axisSpec(leftSeries, tStart, tEnd, gridSteps)
    val rightAxis = axisSpec(rightSeries, tStart, tEnd, gridSteps)
    val left2Axis = axisSpec(left2Series, tStart, tEnd, gridSteps)

    val leftUnit = leftSeries.firstOrNull()?.unit
    val rightUnit = rightSeries.firstOrNull()?.unit
    val left2Unit = left2Series.firstOrNull()?.unit

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
    ) {
        // Top legend: series labels and current values.
        SeriesLegend(
            leftSeries = left2Series + leftSeries,
            rightSeries = rightSeries,
            crosshairMs = crosshairMs,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )

        Canvas(
            // NO horizontal padding: the chart's local x=0 must match the
            // parent's x=0 so the (plotLeftPx, plotWidthPx) reported via
            // onPlotLayout translates pointer-event x correctly.
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 4.dp),
        ) {
            // Y-axis tick labels (left + right).
            val labelPaint = android.graphics.Paint().apply {
                this.color = android.graphics.Color.argb(0xFF, 0xB0, 0xB0, 0xB0)
                textSize = with(density) { 10.sp.toPx() }
                isAntiAlias = true
            }

            // Size each axis gutter to its widest actual label so the plot
            // is as wide as possible (no fixed guess). A small margin sits
            // either side of the text.
            val labelMargin = with(density) { 4.dp.toPx() }
            // Baseline for the bottom label row (x-axis times + the y-axis
            // unit labels, which live here so they don't collide with the
            // topmost value label).
            val bottomLabelOffset = with(density) { 11.dp.toPx() }
            fun gutterFor(spec: AxisSpec, present: Boolean): Float {
                if (!present) return with(density) { 8.dp.toPx() }
                var w = 0f
                for (k in 0..gridSteps) w = maxOf(w, labelPaint.measureText(spec.labelAt(k, gridSteps)))
                return w + labelMargin * 2
            }
            val left2Pad = gutterFor(left2Axis, left2Series.isNotEmpty())
            val leftGutter = gutterFor(leftAxis, leftSeries.isNotEmpty())
            val leftPad = if (left2Series.isNotEmpty()) left2Pad + leftGutter else leftGutter
            val rightPad = gutterFor(rightAxis, rightSeries.isNotEmpty())
            val topPad = with(density) { 6.dp.toPx() }
            // Bottom pad reserves room for the time-of-day x-axis labels.
            val bottomPad = with(density) { 16.dp.toPx() }
            val plotLeft = leftPad
            val plotRight = size.width - rightPad
            val plotTop = topPad
            val plotBottom = size.height - bottomPad
            val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
            val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

            onPlotLayout(plotLeft, plotW)

            // Plot frame + horizontal grid.
            drawLine(AxisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), 1f)
            drawLine(AxisColor, Offset(plotRight, plotTop), Offset(plotRight, plotBottom), 1f)
            drawLine(AxisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), 1f)
            for (k in 0..gridSteps) {
                val y = plotTop + plotH * k / gridSteps
                drawLine(GridColor, Offset(plotLeft, y), Offset(plotRight, y), 1f)
            }
            if (left2Series.isNotEmpty()) {
                // Outer (second) left axis: labels in the extra gutter,
                // just left of the primary left-axis column.
                for (k in 0..gridSteps) {
                    val y = plotTop + plotH * k / gridSteps
                    drawContext.canvas.nativeCanvas.drawText(
                        left2Axis.labelAt(k, gridSteps), labelMargin, y + 4f, labelPaint
                    )
                }
                if (left2Unit != null) {
                    drawContext.canvas.nativeCanvas.drawText(
                        left2Unit.label, labelMargin, plotBottom + bottomLabelOffset, labelPaint
                    )
                }
            }
            if (leftSeries.isNotEmpty()) {
                // Primary left axis labels sit just inside plotLeft; when a
                // second axis is present they're offset by its gutter.
                val x0 = (if (left2Series.isNotEmpty()) left2Pad else 0f) + labelMargin
                for (k in 0..gridSteps) {
                    val y = plotTop + plotH * k / gridSteps
                    drawContext.canvas.nativeCanvas.drawText(
                        leftAxis.labelAt(k, gridSteps), x0, y + 4f, labelPaint
                    )
                }
                if (leftUnit != null) {
                    drawContext.canvas.nativeCanvas.drawText(
                        leftUnit.label, x0, plotBottom + bottomLabelOffset, labelPaint
                    )
                }
            }
            if (rightSeries.isNotEmpty()) {
                val rightPaint = android.graphics.Paint(labelPaint).apply {
                    textAlign = android.graphics.Paint.Align.LEFT
                }
                for (k in 0..gridSteps) {
                    val y = plotTop + plotH * k / gridSteps
                    drawContext.canvas.nativeCanvas.drawText(
                        rightAxis.labelAt(k, gridSteps), plotRight + labelMargin, y + 4f, rightPaint
                    )
                }
                if (rightUnit != null) {
                    drawContext.canvas.nativeCanvas.drawText(
                        rightUnit.label, plotRight + labelMargin, plotBottom + bottomLabelOffset, rightPaint
                    )
                }
            }

            val tSpan = (tEnd - tStart).coerceAtLeast(1L)
            fun xOf(ms: Long): Float =
                plotLeft + plotW * ((ms - tStart).toDouble() / tSpan).toFloat()

            // X-axis ticks + time-of-day labels along the bottom.
            run {
                val intervalMs = pickTickIntervalMs(tEnd - tStart)
                val fmt = pickTickFormat(intervalMs)
                val xPaint = android.graphics.Paint(labelPaint).apply {
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                val tickHalfHeight = with(density) { 3.dp.toPx() }
                var t = firstTickAtOrAfter(tStart, intervalMs)
                while (t <= tEnd) {
                    val x = xOf(t)
                    if (x in plotLeft..plotRight) {
                        drawLine(
                            GridColor,
                            Offset(x, plotTop), Offset(x, plotBottom),
                            strokeWidth = 1f,
                        )
                        drawLine(
                            AxisColor,
                            Offset(x, plotBottom - tickHalfHeight),
                            Offset(x, plotBottom + tickHalfHeight),
                            strokeWidth = 1f,
                        )
                        drawContext.canvas.nativeCanvas.drawText(
                            fmt.format(java.util.Date(t)),
                            x, plotBottom + bottomLabelOffset, xPaint,
                        )
                    }
                    t += intervalMs
                }
            }

            // Series polylines.
            drawSeries(
                series = left2Series,
                xOf = ::xOf,
                yMin = left2Axis.min, yMax = left2Axis.max,
                plotTop = plotTop, plotBottom = plotBottom, plotLeft = plotLeft, plotRight = plotRight,
                tStart = tStart, tEnd = tEnd,
            )
            drawSeries(
                series = leftSeries,
                xOf = ::xOf,
                yMin = leftAxis.min, yMax = leftAxis.max,
                plotTop = plotTop, plotBottom = plotBottom, plotLeft = plotLeft, plotRight = plotRight,
                tStart = tStart, tEnd = tEnd,
            )
            drawSeries(
                series = rightSeries,
                xOf = ::xOf,
                yMin = rightAxis.min, yMax = rightAxis.max,
                plotTop = plotTop, plotBottom = plotBottom, plotLeft = plotLeft, plotRight = plotRight,
                tStart = tStart, tEnd = tEnd,
            )

            // Crosshair line + marker dots on every series.
            if (crosshairMs != null && crosshairMs in tStart..tEnd) {
                val cx = xOf(crosshairMs).coerceIn(plotLeft, plotRight)
                drawLine(
                    CrosshairColor,
                    Offset(cx, plotTop),
                    Offset(cx, plotBottom),
                    strokeWidth = 1f,
                )
                drawMarkers(left2Series, crosshairMs, left2Axis.min, left2Axis.max, cx, plotTop, plotBottom)
                drawMarkers(leftSeries, crosshairMs, leftAxis.min, leftAxis.max, cx, plotTop, plotBottom)
                drawMarkers(rightSeries, crosshairMs, rightAxis.min, rightAxis.max, cx, plotTop, plotBottom)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    series: List<PlottedSeries>,
    xOf: (Long) -> Float,
    yMin: Double,
    yMax: Double,
    plotTop: Float,
    plotBottom: Float,
    plotLeft: Float,
    plotRight: Float,
    tStart: Long,
    tEnd: Long,
) {
    if (series.isEmpty()) return
    val ySpan = (yMax - yMin).coerceAtLeast(1e-9)
    val plotH = plotBottom - plotTop
    fun yOf(v: Double): Float =
        plotTop + plotH * ((yMax - v) / ySpan).toFloat()

    for (s in series) {
        val snap = s.snapshot
        if (snap.size == 0) continue
        val lo = snap.lowerBound(tStart)
        val hiExclusive = snap.upperBound(tEnd)
        val visStart = max(0, lo - 1)
        val visEnd = min(snap.size - 1, hiExclusive)
        if (visEnd < visStart) continue

        val path = Path()
        var penDown = false
        for (k in visStart..visEnd) {
            val v = s.read(k)
            if (v == null) {
                penDown = false
                continue
            }
            val t = snap.timestampAt(k)
            val x = xOf(t).coerceIn(plotLeft, plotRight)
            val y = yOf(v).coerceIn(plotTop, plotBottom)
            if (!penDown) {
                path.moveTo(x, y); penDown = true
            } else {
                path.lineTo(x, y)
            }
        }
        drawPath(path, color = s.color, style = Stroke(width = 2f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMarkers(
    series: List<PlottedSeries>,
    crosshairMs: Long,
    yMin: Double,
    yMax: Double,
    cx: Float,
    plotTop: Float,
    plotBottom: Float,
) {
    if (series.isEmpty()) return
    val ySpan = (yMax - yMin).coerceAtLeast(1e-9)
    val plotH = plotBottom - plotTop
    for (s in series) {
        val idx = nearestIndex(s.snapshot, crosshairMs)
        if (idx < 0) continue
        val v = s.read(idx) ?: continue
        val y = (plotTop + plotH * ((yMax - v) / ySpan).toFloat()).coerceIn(plotTop, plotBottom)
        drawCircle(s.color, radius = 3.5f, center = Offset(cx, y))
    }
}

@Composable
private fun SeriesLegend(
    leftSeries: List<PlottedSeries>,
    rightSeries: List<PlottedSeries>,
    crosshairMs: Long?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (s in leftSeries + rightSeries) {
            val v = displayedValueFor(s, crosshairMs)
            val text = if (v != null) "${s.label} ${s.format.format(v)}" else "${s.label} —"
            Text(
                text = text,
                color = s.color,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

private fun displayedValueFor(s: PlottedSeries, crosshairMs: Long?): Double? {
    val snap = s.snapshot
    if (snap.size == 0) return null
    val idx = if (crosshairMs != null) nearestIndex(snap, crosshairMs) else snap.size - 1
    return if (idx >= 0) s.read(idx) else null
}

/** Resolved Y-axis: bounds, label format, and the label at each gridline. */
internal data class AxisSpec(val min: Double, val max: Double, val format: String) {
    /** Number of gridline intervals — labels are drawn at k = 0..steps. */
    fun labelAt(k: Int, steps: Int): String =
        format.format(max - (max - min) * k / steps)
}

/**
 * Gather the finite samples of a series group over the visible window.
 * Returns null when the group is empty or has no data.
 */
private fun finiteValues(series: List<PlottedSeries>, tStart: Long, tEnd: Long): List<Double> {
    val finite = ArrayList<Double>()
    for (s in series) {
        val snap = s.snapshot
        if (snap.size == 0) continue
        val lo = snap.lowerBound(tStart)
        val hiExclusive = snap.upperBound(tEnd)
        val visStart = max(0, lo - 1)
        val visEnd = min(snap.size - 1, hiExclusive)
        for (k in visStart..visEnd) {
            val v = s.read(k) ?: continue
            finite += v
        }
    }
    return finite
}

/**
 * Build the axis spec for a series group, over [gridSteps] intervals.
 *
 * KNOTS and PERCENT get whole-number labels: the span is forced to a
 * multiple of [gridSteps] so every gridline lands on an integer, and to
 * at least a unit-specific minimum (2 kn / 10 %). Other units keep the
 * padded "nice" range with a decimal format.
 */
internal fun axisSpec(
    series: List<PlottedSeries>,
    tStart: Long,
    tEnd: Long,
    gridSteps: Int,
): AxisSpec {
    val unit = series.firstOrNull()?.unit
    val finite = finiteValues(series, tStart, tEnd)

    return when (unit) {
        UnitGroup.KNOTS -> wholeUnitAxis(finite, minRange = 2.0, gridSteps, fallbackLo = 0.0, fallbackHi = 6.0)
        UnitGroup.PERCENT -> wholeUnitAxis(finite, minRange = 10.0, gridSteps, fallbackLo = 0.0, fallbackHi = 100.0)
        else -> {
            val (lo, hi) = niceRange(finite, 0.0, 1.0, minSpan = 0.1, includeZero = false)
            AxisSpec(lo, hi, pickAxisFormat(lo, hi))
        }
    }
}

/**
 * Whole-number axis: integer bounds, span a multiple of [gridSteps] (so
 * each gridline label is an integer), and at least [minRange] tall.
 */
private fun wholeUnitAxis(
    finite: List<Double>,
    minRange: Double,
    gridSteps: Int,
    fallbackLo: Double,
    fallbackHi: Double,
): AxisSpec {
    var lo = if (finite.isEmpty()) fallbackLo else finite.min()
    var hi = if (finite.isEmpty()) fallbackHi else finite.max()
    // Pad to the minimum range about the centre.
    if (hi - lo < minRange) {
        val mid = (hi + lo) / 2
        lo = mid - minRange / 2
        hi = mid + minRange / 2
    }
    val minInt = kotlin.math.floor(lo).toInt()
    // Span up to cover the data, rounded up to a multiple of gridSteps so
    // each of the gridSteps+1 labels is a whole number. Never below the
    // (already whole) minimum range.
    var span = kotlin.math.ceil(hi - minInt).toInt().coerceAtLeast(kotlin.math.ceil(minRange).toInt())
    if (span % gridSteps != 0) span += gridSteps - (span % gridSteps)
    if (span < gridSteps) span = gridSteps
    return AxisSpec(minInt.toDouble(), (minInt + span).toDouble(), "%.0f")
}

/**
 * Pick a `String.format` pattern wide enough to display `lo..hi` without
 * losing precision.
 */
private fun pickAxisFormat(lo: Double, hi: Double): String {
    val span = abs(hi - lo)
    return when {
        span >= 100.0 -> "%.0f"
        span >= 10.0 -> "%.1f"
        span >= 1.0 -> "%.2f"
        else -> "%.3f"
    }
}
