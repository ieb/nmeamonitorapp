package uk.co.tfd.nmeamonitor.nmea

/**
 * Boat polar loaded from a CSV file. Bilinear interpolation over
 * arbitrary (possibly non-uniform) TWS and TWA axes. Mirrors the
 * firmware interpolation order in pogo1250polar.h / performance.cpp:
 * interpolate along TWA first (inside), then blend along TWS (outside).
 *
 * Values below the lowest TWA in the table return the table value at
 * that lowest angle (typically 0 kn).
 */
class PolarTable(
    val name: String,
    val twsAxis: DoubleArray,
    val twaAxis: DoubleArray,
    // speeds[twaIdx][twsIdx], knots
    val speeds: Array<DoubleArray>
) {

    fun polarSpeed(twsKn: Double, absTwaDeg: Double): Double {
        val (twsLo, twsHi) = findBracket(twsKn, twsAxis)
        val (twaLo, twaHi) = findBracket(absTwaDeg, twaAxis)

        val xTws0 = twsAxis[twsLo]
        val xTws1 = twsAxis[twsHi]
        val xTwa0 = twaAxis[twaLo]
        val xTwa1 = twaAxis[twaHi]

        val s00 = speeds[twaLo][twsLo]
        val s01 = speeds[twaLo][twsHi]
        val s10 = speeds[twaHi][twsLo]
        val s11 = speeds[twaHi][twsHi]

        val sLo = interpolate(absTwaDeg, xTwa0, xTwa1, s00, s10)
        val sHi = interpolate(absTwaDeg, xTwa0, xTwa1, s01, s11)
        return interpolate(twsKn, xTws0, xTws1, sLo, sHi)
    }

    private fun findBracket(v: Double, axis: DoubleArray): Pair<Int, Int> {
        if (v <= axis[0]) return 0 to 0
        for (i in 1 until axis.size) {
            if (axis[i] > v) return (i - 1) to i
        }
        val last = axis.size - 1
        return last to last
    }

    private fun interpolate(x: Double, xl: Double, xh: Double, yl: Double, yh: Double): Double {
        return when {
            x >= xh -> yh
            x <= xl -> yl
            kotlin.math.abs(xh - xl) < 1.0e-4 -> (yl + yh) / 2
            else -> yl + (yh - yl) * ((x - xl) / (xh - xl))
        }
    }

    companion object {
        /**
         * Parse an Expedition/ORC-style polar CSV. First non-comment row is
         * the TWS header (first cell ignored/label). Each subsequent row
         * starts with a TWA value followed by speeds in knots. Delimiter is
         * auto-detected from `;`, tab, or `,`. Lines starting with `#` or
         * blank lines are ignored.
         */
        fun parseCsv(name: String, text: String): Result<PolarTable> = runCatching {
            val lines = text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
            require(lines.size >= 2) { "polar must have a header plus at least one row" }

            val delim = detectDelimiter(lines[0])
            val header = lines[0].split(delim).map { it.trim() }
            require(header.size >= 2) { "header needs at least one TWS column" }
            val twsAxis = DoubleArray(header.size - 1) { header[it + 1].toDouble() }
            requireMonotonic(twsAxis, "TWS")

            val twaList = ArrayList<Double>(lines.size - 1)
            val speedRows = ArrayList<DoubleArray>(lines.size - 1)
            for (row in lines.drop(1)) {
                val cells = row.split(delim).map { it.trim() }
                require(cells.size == header.size) {
                    "row has ${cells.size} cells, expected ${header.size}: $row"
                }
                val twa = cells[0].toDouble()
                val speeds = DoubleArray(twsAxis.size) { idx ->
                    val v = cells[idx + 1].toDouble()
                    require(v >= 0) { "negative speed $v in row TWA=$twa" }
                    v
                }
                twaList += twa
                speedRows += speeds
            }
            val twaAxis = twaList.toDoubleArray()
            requireMonotonic(twaAxis, "TWA")

            PolarTable(name, twsAxis, twaAxis, speedRows.toTypedArray())
        }

        private fun detectDelimiter(headerLine: String): String {
            return when {
                ';' in headerLine -> ";"
                '\t' in headerLine -> "\t"
                ',' in headerLine -> ","
                else -> throw IllegalArgumentException("cannot detect delimiter in header: $headerLine")
            }
        }

        private fun requireMonotonic(axis: DoubleArray, label: String) {
            require(axis.isNotEmpty()) { "$label axis is empty" }
            for (i in 1 until axis.size) {
                require(axis[i] > axis[i - 1]) {
                    "$label axis must be strictly ascending (index $i: ${axis[i - 1]} -> ${axis[i]})"
                }
            }
        }
    }
}
