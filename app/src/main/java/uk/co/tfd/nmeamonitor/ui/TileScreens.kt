package uk.co.tfd.nmeamonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import uk.co.tfd.nmeamonitor.nmea.BatteryState
import uk.co.tfd.nmeamonitor.nmea.EngineState
import uk.co.tfd.nmeamonitor.nmea.NavigationState
import uk.co.tfd.nmeamonitor.nmea.Performance
import uk.co.tfd.nmeamonitor.nmea.Polars

@Composable
private fun ScreenColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun NavigationTiles(nav: NavigationState?, viewModel: MonitorViewModel) {
    val context = LocalContext.current
    val polar = remember { Polars.builtIn(context) }
    // Live polar-speed ratio (%) from the current nav frame + bundled polar.
    val polarPct: Double? = if (nav != null && polar != null) {
        Performance.derive(nav, polar)?.polarSpeedRatio?.let { it * 100.0 }
    } else null

    ScreenColumn {
        FullWidthTile("POSITION", formatPosition(nav), valueSp = 22)
        TileRow("STW", formatSpeedKn(nav?.stw), "POLAR %", formatPercent(polarPct))
        TileRow("AWS", formatSpeedKn(nav?.aws), "AWA", formatAnglePM180(nav?.awa))
        TileRow("SOG", formatSpeedKn(nav?.sog), "COG", formatAngle360(nav?.cog))
        Text(
            text = formatVariation(nav?.variation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Scrollable STW / Polar % / Depth history graph (3 Y axes).
        NavChart(viewModel = viewModel)
    }
}

@Composable
fun EngineTiles(engine: EngineState?, battery: BatteryState?, viewModel: MonitorViewModel) {
    ScreenColumn {
        AlarmBanner(engine?.alarms?.map { it.label } ?: emptyList())
        TileRow("RPM", formatRpm(engine?.rpm), "COOLANT", formatTempC(engine?.coolantC))
        TileRow("EXHAUST", formatTempC(engine?.exhaustC), "ALT TEMP", formatTempC(engine?.alternatorC))
        // Prefer alternator voltage when the firmware reports it; fall
        // back to the engine battery voltage otherwise.
        val voltLabel = if (engine?.alternatorV != null) "ALT V" else "BATT"
        val voltValue = formatVolts1(engine?.alternatorV ?: engine?.engineBattV)
        TileRow(voltLabel, voltValue, "BMS CURR", formatSignedCurrent(battery?.currentA))
        // Scrollable temperature + RPM history graph, with fuel % and
        // engine hours shown inline beside the window controls.
        EngineChart(engine = engine, viewModel = viewModel)
    }
}

@Composable
fun BatteryTiles(battery: BatteryState?, viewModel: MonitorViewModel) {
    ScreenColumn {
        AlarmBanner(battery?.alarms?.map { it.label } ?: emptyList())
        TileRow("PACK", formatVolts(battery?.packV), "CURRENT", formatSignedCurrent(battery?.currentA))
        TileRow("SOC", formatSoc(battery?.soc), "CELL Δ", formatCellDelta(battery?.cellVoltagesV))
        TileRow("REMAINING", formatAh(battery?.remainingAh), "CAPACITY", formatAh(battery?.fullAh))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip("CHARGE", battery?.chargeFet == true, Modifier.weight(1f))
            StatusChip("DISCHARGE", battery?.dischargeFet == true, Modifier.weight(1f))
        }
        // Scrollable voltage + current history graph.
        BatteryChart(viewModel = viewModel)
    }
}
