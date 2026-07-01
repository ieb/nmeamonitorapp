package uk.co.tfd.nmeamonitor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color
import uk.co.tfd.nmeamonitor.history.HistoryDataSource
import uk.co.tfd.nmeamonitor.nmea.EngineProtocol
import uk.co.tfd.nmeamonitor.nmea.EngineState

private val RpmColor = Color(0xFF4FC3F7)      // light blue
private val CoolantColor = Color(0xFFE57373)  // red
private val ExhaustColor = Color(0xFFFF7043)  // deep orange
private val AltTempColor = Color(0xFFFFD54F)  // yellow — alternator temperature

/**
 * Scrollable engine chart: RPM on the left axis, coolant + exhaust +
 * alternator temperature on the right °C axis. Fuel % and engine hours
 * are shown right-aligned beside the window controls.
 */
@Composable
fun EngineChart(
    engine: EngineState?,
    viewModel: MonitorViewModel,
    modifier: Modifier = Modifier,
) {
    ScrollableHistoryChart(
        viewModel = viewModel,
        dataSourceFactory = { HistoryDataSource.forEngine(it) },
        modifier = modifier,
        series = { snap ->
            val left = listOf(
                PlottedSeries(
                    label = "RPM", color = RpmColor, unit = UnitGroup.RPM, format = "%.0f",
                    snapshot = snap, read = { i -> EngineProtocol.rpmAt(snap, i)?.toDouble() },
                )
            )
            val right = listOf(
                PlottedSeries(
                    label = "Coolant", color = CoolantColor, unit = UnitGroup.TEMP_C, format = "%.0f",
                    snapshot = snap, read = { i -> EngineProtocol.coolantCAt(snap, i) },
                ),
                PlottedSeries(
                    label = "Exhaust", color = ExhaustColor, unit = UnitGroup.TEMP_C, format = "%.0f",
                    snapshot = snap, read = { i -> EngineProtocol.exhaustCAt(snap, i) },
                ),
                PlottedSeries(
                    label = "Alt", color = AltTempColor, unit = UnitGroup.TEMP_C, format = "%.0f",
                    snapshot = snap, read = { i -> EngineProtocol.alternatorCAt(snap, i) },
                ),
            )
            ChartSeries(left = left, right = right)
        },
        controlsTrailing = {
            // Fuel % and engine hours, right-aligned and stacked in two
            // rows: "Fuel 74 %" on top, the raw hours (no label) below.
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Fuel ${formatPercent(engine?.fuelPct)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatHours(engine?.engineHoursSec),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
