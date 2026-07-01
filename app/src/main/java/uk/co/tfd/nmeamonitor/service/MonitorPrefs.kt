package uk.co.tfd.nmeamonitor.service

import android.content.Context

/**
 * Single source of truth for the persisted connection settings, shared by
 * the ViewModel (writes on device selection) and the service (reads on a
 * START_STICKY restart, when the OS relaunches us with a null intent and
 * the original extras are gone).
 */
object MonitorPrefs {
    const val PREFS_NAME = "nmea_monitor"
    const val KEY_ADDRESS = "ble_address"
    const val KEY_PIN = "ble_pin"

    fun address(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ADDRESS, "") ?: ""

    fun pin(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PIN, "0000") ?: "0000"

    fun save(context: Context, address: String, pin: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_PIN, pin)
            .apply()
    }
}
