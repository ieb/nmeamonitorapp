package uk.co.tfd.nmeamonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** A single big-number tile spanning the full row width. */
@Composable
fun FullWidthTile(label: String, value: String, valueSp: Int = 32) {
    BigNumberTile(
        label = label,
        value = value,
        modifier = Modifier.fillMaxWidth(),
        valueSp = valueSp,
    )
}

/** Two big-number tiles side by side, each taking half the width. */
@Composable
fun TileRow(
    leftLabel: String, leftValue: String,
    rightLabel: String, rightValue: String,
    valueSp: Int = 40,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        BigNumberTile(leftLabel, leftValue, Modifier.weight(1f), valueSp)
        BigNumberTile(rightLabel, rightValue, Modifier.weight(1f), valueSp)
    }
}

/** Prominent banner listing active alarms; nothing rendered when empty. */
@Composable
fun AlarmBanner(labels: List<String>) {
    if (labels.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "⚠ " + labels.joinToString("  •  "),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

/** A small on/off status chip (e.g. charge/discharge FET). */
@Composable
fun StatusChip(label: String, on: Boolean, modifier: Modifier = Modifier) {
    val bg = if (on) Color(0xFF1B5E20) else Color(0xFF7F1D1D)
    val fg = Color.White
    Surface(
        modifier = modifier,
        color = bg,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            text = "$label  ${if (on) "ON" else "OFF"}",
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}
