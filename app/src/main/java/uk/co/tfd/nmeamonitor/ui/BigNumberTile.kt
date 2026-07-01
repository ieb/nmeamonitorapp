package uk.co.tfd.nmeamonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A high-contrast label/value tile sized for glancing at arm's length on
 * deck. The value uses a large monospace font so digits don't jump around
 * as they update. When a value is too wide for the tile (e.g. "13.69 V" in
 * a half-width tile), the font auto-shrinks to keep it on a single line
 * rather than wrapping the unit onto the digits.
 */
@Composable
fun BigNumberTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueSp: Int = 40,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AutoSizeValue(value = value, maxSp = valueSp)
        }
    }
}

/**
 * Draws [value] on one line, choosing the largest font (down to a floor)
 * that fits the tile width. The size is measured synchronously in a single
 * pass — the text is never hidden and never re-laid-out iteratively, so it
 * doesn't flicker as the value updates.
 *
 * The fitting size is keyed on the character count, not the string: with a
 * monospace font, "4.7 kn" and "4.8 kn" are the same width, so a value that
 * updates without changing length reuses the cached size and just redraws
 * the glyphs in place.
 */
@Composable
private fun AutoSizeValue(value: String, maxSp: Int) {
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val minSp = 18f

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth
        val fontSp = remember(value.length, maxSp, widthPx) {
            var sp = maxSp.toFloat()
            while (sp > minSp) {
                val result = measurer.measure(
                    text = value,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = sp.sp),
                    maxLines = 1,
                    softWrap = false,
                    constraints = Constraints(maxWidth = widthPx),
                )
                if (!result.hasVisualOverflow) break
                sp -= 2f
            }
            sp
        }

        Text(
            text = value,
            fontSize = fontSp.sp,
            fontFamily = FontFamily.Monospace,
            color = onSurface,
            maxLines = 1,
            softWrap = false,
        )
    }
}
