package uk.co.tfd.nmeamonitor.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import uk.co.tfd.nmeamonitor.history.HistoryDataSource
import uk.co.tfd.nmeamonitor.history.RingSnapshot

private const val LIVE_REFRESH_MS = 1000L

private data class WindowPreset(val label: String, val ms: Long)

private val PRESETS = listOf(
    WindowPreset("1m", 60_000L),
    WindowPreset("10m", 10L * 60_000L),
    WindowPreset("1h", 60L * 60_000L),
)

/** The three axis groups a chart plots: an optional second-left axis, the
 *  primary left axis, and the right axis. */
data class ChartSeries(
    val left2: List<PlottedSeries> = emptyList(),
    val left: List<PlottedSeries>,
    val right: List<PlottedSeries>,
)

/**
 * A scrollable time-series chart with a shared control row (1m / 10m / 1h
 * presets + Live) and pan / tap gestures, all driven by the shared chart
 * window state on [MonitorViewModel]. Used by the Nav, Engine and Battery
 * screens.
 *
 * The caller supplies:
 *  - [dataSourceFactory] — builds a [HistoryDataSource] for its stream from
 *    the bound service's history directory (remembered on the dir);
 *  - [series] — given the loaded snapshot, returns the axis groups to plot;
 *  - [controlsTrailing] — optional composable placed at the right edge of
 *    the control row (e.g. the Engine screen's Fuel / hours readouts).
 */
@Composable
fun ScrollableHistoryChart(
    viewModel: MonitorViewModel,
    dataSourceFactory: (java.io.File) -> HistoryDataSource,
    series: (RingSnapshot) -> ChartSeries,
    modifier: Modifier = Modifier,
    controlsTrailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val historyDir by viewModel.historyDir.collectAsState()
    val windowMs by viewModel.chartWindowMs.collectAsState()
    val endMs by viewModel.chartEndMs.collectAsState()
    val crosshairMs by viewModel.chartCrosshairMs.collectAsState()

    val dir = historyDir
    if (dir == null) {
        Column(modifier = modifier.fillMaxWidth()) {
            Text(
                "Graph available once connected",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
        return
    }

    val dataSource = remember(dir) { dataSourceFactory(dir) }

    // Live-tail tick: while pinned to live (endMs == null) advance nowTick
    // every second so the window follows freshly-written frames.
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endMs, dataSource) {
        if (endMs == null) {
            while (true) {
                nowTick = System.currentTimeMillis()
                dataSource.invalidate()   // force a re-read of the growing tail
                delay(LIVE_REFRESH_MS)
            }
        }
    }
    val tEnd = endMs ?: nowTick
    val tStart = tEnd - windowMs

    val snapshot: RingSnapshot = androidx.compose.runtime.produceState(
        initialValue = RingSnapshot.EMPTY,
        key1 = tStart, key2 = tEnd, key3 = dataSource,
    ) {
        value = try {
            dataSource.loadWindow(tStart, tEnd)
        } catch (_: Throwable) {
            RingSnapshot.EMPTY
        }
    }.value

    // Plot geometry reported by the chart, used to convert drag px → ms.
    var plotLeftPx by remember { mutableFloatStateOf(0f) }
    var plotWidthPx by remember { mutableFloatStateOf(1f) }

    val axes = series(snapshot)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (p in PRESETS) {
                FilterChip(
                    selected = windowMs == p.ms,
                    onClick = { viewModel.setChartWindow(p.ms) },
                    label = { Text(p.label) },
                )
            }
            FilterChip(
                selected = endMs == null,
                onClick = { viewModel.snapChartLive() },
                label = { Text(if (endMs == null) "● Live" else "Live") },
            )
            if (controlsTrailing != null) {
                Spacer(Modifier.weight(1f))
                controlsTrailing()
            }
        }

        MultiSeriesChart(
            leftSeries = axes.left,
            rightSeries = axes.right,
            left2Series = axes.left2,
            tStart = tStart,
            tEnd = tEnd,
            crosshairMs = crosshairMs,
            onPlotLayout = { l, w -> plotLeftPx = l; plotWidthPx = w },
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                // Horizontal drag pans through time. detectHorizontalDrag
                // consumes the gesture, so the enclosing HorizontalPager
                // won't page while panning the chart.
                .pointerInput(windowMs, plotWidthPx) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        val msPerPx = windowMs.toDouble() / plotWidthPx.coerceAtLeast(1f)
                        val deltaMs = (-dragAmount * msPerPx).toLong()
                        viewModel.panChartBy(deltaMs, System.currentTimeMillis())
                    }
                }
                // Tap pins the crosshair at that time.
                .pointerInput(tStart, tEnd, plotLeftPx, plotWidthPx) {
                    detectTapGestures { offset ->
                        val frac = ((offset.x - plotLeftPx) / plotWidthPx.coerceAtLeast(1f))
                            .coerceIn(0f, 1f)
                        val ms = tStart + (frac * (tEnd - tStart)).toLong()
                        viewModel.setChartCrosshair(ms)
                    }
                },
        )
    }
}
