package uk.co.tfd.nmeamonitor.nmea

import android.content.Context

/**
 * Loads the bundled built-in polar (pogo1250) from assets, once, and
 * caches it. This quick-glance app ships a single fixed polar — there is
 * no polar library or import UI (unlike the full nmeabridge app).
 */
object Polars {

    private const val ASSET = "polars/pogo1250.csv"
    private const val NAME = "pogo1250"

    @Volatile private var cached: PolarTable? = null

    /** The built-in polar, or null if the asset is missing / malformed. */
    fun builtIn(context: Context): PolarTable? {
        cached?.let { return it }
        return try {
            val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            PolarTable.parseCsv(NAME, text).getOrNull()?.also { cached = it }
        } catch (_: Exception) {
            null
        }
    }
}
