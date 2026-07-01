package uk.co.tfd.nmeamonitor.service

import uk.co.tfd.nmeamonitor.nmea.BatteryState
import uk.co.tfd.nmeamonitor.nmea.EngineState
import uk.co.tfd.nmeamonitor.nmea.NavigationState

/**
 * Everything the UI needs from the running service in one immutable snapshot.
 * [connected] is true once the GATT link is up (and, on BoatWatch firmware,
 * authenticated). [status] carries transient human-readable connection text
 * ("Connecting…", "Authenticating…", errors) surfaced by the BLE source.
 */
data class MonitorState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val status: String? = null,
    val deviceAddress: String = "",
    val navigationState: NavigationState? = null,
    val batteryState: BatteryState? = null,
    val engineState: EngineState? = null,
)
