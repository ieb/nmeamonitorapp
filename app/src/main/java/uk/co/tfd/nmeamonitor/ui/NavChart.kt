package uk.co.tfd.nmeamonitor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import uk.co.tfd.nmeamonitor.history.HistoryDataSource
import uk.co.tfd.nmeamonitor.history.RingSnapshot
import uk.co.tfd.nmeamonitor.nmea.BinaryProtocol
import uk.co.tfd.nmeamonitor.nmea.NavigationState
import uk.co.tfd.nmeamonitor.nmea.Performance
import uk.co.tfd.nmeamonitor.nmea.PolarTable

private val StwColor = Color(0xFF4FC3F7)     // light blue — speed through water
private val PolarColor = Color(0xFFBA68C8)   // purple — polar %
private val DepthColor = Color(0xFF81C784)   // green — depth

/**
 * Scrollable nav chart with three independent Y axes: STW (kn) and
 * Polar % (%) each on their own left scale, Depth (m) on the right.
 *
 * Polar % is recomputed per historical sample from that sample's
 * awa/aws/stw against the bundled polar — there is no stored polar-ratio
 * field, and it can't be interpolated from neighbours meaningfully.
 */
@Composable
fun NavChart(
    viewModel: MonitorViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val polar = remember { uk.co.tfd.nmeamonitor.nmea.Polars.builtIn(context) }

    ScrollableHistoryChart(
        viewModel = viewModel,
        dataSourceFactory = { HistoryDataSource.forNav(it) },
        modifier = modifier,
        series = { snap ->
            val stw = PlottedSeries(
                label = "STW", color = StwColor, unit = UnitGroup.KNOTS, format = "%.1f",
                snapshot = snap, read = { i -> BinaryProtocol.stwAt(snap, i) },
            )
            val polarPct = PlottedSeries(
                label = "Polar%", color = PolarColor, unit = UnitGroup.PERCENT, format = "%.0f",
                snapshot = snap, read = { i -> polarRatioAt(snap, i, polar) },
            )
            val depth = PlottedSeries(
                label = "Depth", color = DepthColor, unit = UnitGroup.METRE, format = "%.1f",
                snapshot = snap, read = { i -> BinaryProtocol.depthAt(snap, i) },
            )
            ChartSeries(
                left2 = listOf(stw),        // outer left axis: knots
                left = listOf(polarPct),    // inner left axis: %
                right = listOf(depth),      // right axis: metres
            )
        },
    )
}

/**
 * Polar-speed ratio (%) at history sample [i]: the boat's STW as a
 * percentage of the polar-predicted speed for that sample's true wind.
 * Null when the sample lacks awa/aws/stw or the polar predicts ~0.
 */
private fun polarRatioAt(snap: RingSnapshot, i: Int, polar: PolarTable?): Double? {
    if (polar == null) return null
    val awa = BinaryProtocol.awaAt(snap, i) ?: return null
    val aws = BinaryProtocol.awsAt(snap, i) ?: return null
    val stw = BinaryProtocol.stwAt(snap, i) ?: return null
    val derived = Performance.derive(
        NavigationState(awa = awa, aws = aws, stw = stw), polar
    ) ?: return null
    return derived.polarSpeedRatio?.let { it * 100.0 }
}
