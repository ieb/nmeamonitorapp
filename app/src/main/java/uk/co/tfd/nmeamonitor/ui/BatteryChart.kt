package uk.co.tfd.nmeamonitor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import uk.co.tfd.nmeamonitor.history.HistoryDataSource
import uk.co.tfd.nmeamonitor.nmea.BmsProtocol

private val VoltColor = Color(0xFF81C784)    // green — pack voltage
private val CurrentColor = Color(0xFF4FC3F7) // light blue — pack current

/**
 * Scrollable battery chart: pack voltage on the left axis, pack current
 * on the right axis. Same controls/gestures as the engine chart.
 */
@Composable
fun BatteryChart(
    viewModel: MonitorViewModel,
    modifier: Modifier = Modifier,
) {
    ScrollableHistoryChart(
        viewModel = viewModel,
        dataSourceFactory = { HistoryDataSource.forBms(it) },
        modifier = modifier,
        series = { snap ->
            val left = listOf(
                PlottedSeries(
                    label = "Volts", color = VoltColor, unit = UnitGroup.VOLT, format = "%.2f",
                    snapshot = snap, read = { i -> BmsProtocol.packVAt(snap, i) },
                )
            )
            val right = listOf(
                PlottedSeries(
                    label = "Current", color = CurrentColor, unit = UnitGroup.AMP, format = "%.1f",
                    snapshot = snap, read = { i -> BmsProtocol.currentAAt(snap, i) },
                )
            )
            ChartSeries(left = left, right = right)
        },
    )
}
