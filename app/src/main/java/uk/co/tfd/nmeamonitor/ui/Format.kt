package uk.co.tfd.nmeamonitor.ui

import uk.co.tfd.nmeamonitor.nmea.NavigationState
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Value formatters shared by the tile screens. All null-safe: a missing
 * (or gone-stale) reading renders as "---" rather than a frozen number.
 */

const val NO_VALUE = "---"

fun formatPosition(nav: NavigationState?): String {
    val lat = nav?.latitude
    val lon = nav?.longitude
    if (lat == null || lon == null) return NO_VALUE
    return "${formatDDM(lat, "N", "S")}  ${formatDDM(lon, "E", "W")}"
}

private fun formatDDM(degrees: Double, pos: String, neg: String): String {
    val absDeg = abs(degrees)
    val d = absDeg.toInt()
    val m = (absDeg - d) * 60.0
    val dir = if (degrees >= 0) pos else neg
    val pad = if (pos == "E" || pos == "W") 3 else 2
    return "%0${pad}d°%06.3f'$dir".format(d, m)
}

fun formatAngle360(deg: Double?): String {
    if (deg == null) return NO_VALUE
    val d = ((deg.roundToInt() % 360) + 360) % 360
    return "%03d°".format(d)
}

fun formatAnglePM180(deg: Double?): String {
    if (deg == null) return NO_VALUE
    val side = if (deg >= 0) "S" else "P"
    return "$side%d°".format(abs(deg).roundToInt())
}

fun formatSpeedKn(kn: Double?): String {
    if (kn == null) return NO_VALUE
    return "%.1f kn".format(kn)
}

fun formatDepth(m: Double?): String {
    if (m == null) return NO_VALUE
    return "%.1f m".format(m)
}

fun formatVariation(deg: Double?): String {
    if (deg == null) return "VAR ---"
    val dir = if (deg >= 0) "E" else "W"
    return "VAR %d°$dir".format(abs(deg).roundToInt())
}

fun formatSignedCurrent(a: Double?): String {
    if (a == null) return NO_VALUE
    val sign = if (a >= 0) "+" else "−"
    return "$sign%.1f A".format(abs(a))
}

fun formatVolts(v: Double?): String {
    if (v == null) return NO_VALUE
    return "%.2f V".format(v)
}

fun formatVolts1(v: Double?): String {
    if (v == null) return NO_VALUE
    return "%.1f V".format(v)
}

fun formatRpm(rpm: Int?): String {
    if (rpm == null) return NO_VALUE
    return "%d".format(rpm)
}

fun formatTempC(c: Double?): String {
    if (c == null) return NO_VALUE
    return "%.0f °C".format(c)
}

fun formatBar(bar: Double?): String {
    if (bar == null) return NO_VALUE
    return "%.1f bar".format(bar)
}

fun formatPercent(pct: Double?): String {
    if (pct == null) return NO_VALUE
    return "%.0f %%".format(pct)
}

fun formatHours(seconds: Long?): String {
    if (seconds == null) return NO_VALUE
    return "%.1f h".format(seconds / 3600.0)
}

fun formatAh(ah: Double?): String {
    if (ah == null) return NO_VALUE
    return "%.0f Ah".format(ah)
}

fun formatSoc(soc: Int?): String {
    if (soc == null) return NO_VALUE
    return "%d %%".format(soc)
}

/**
 * Max − min cell voltage spread, in millivolts — the standard measure of
 * pack balance. Null / empty / single-cell packs render as "---".
 */
fun formatCellDelta(cells: List<Double>?): String {
    if (cells == null || cells.size < 2) return NO_VALUE
    val deltaMv = (cells.max() - cells.min()) * 1000.0
    return "%.0f mV".format(deltaMv)
}
