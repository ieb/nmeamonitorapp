package uk.co.tfd.nmeamonitor.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import uk.co.tfd.nmeamonitor.NmeaMonitorApp
import uk.co.tfd.nmeamonitor.bluetooth.BleNmeaSource
import uk.co.tfd.nmeamonitor.history.persist.HistoryLogger
import uk.co.tfd.nmeamonitor.nmea.BmsProtocol
import uk.co.tfd.nmeamonitor.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that owns the BLE link for the app's lifetime. It holds
 * a single [BleNmeaSource], mirrors its decoded StateFlows into a combined
 * [MonitorState], and keeps a partial wake lock so the connection survives
 * the screen turning off. No TCP server — this is a viewer — but it does
 * persist engine frames to disk so the temperature graph has history.
 */
class MonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    // Append-only engine-frame persistence for the temperature graph.
    // Created lazily on first start; frames arrive at ~1 Hz.
    private var logger: HistoryLogger? = null

    /** Directory holding the engine history files; the Engine chart reads it. */
    val historyDir: File get() = ensureLogger().directory

    private fun ensureLogger(): HistoryLogger =
        logger ?: HistoryLogger(this).also { logger = it }

    // BleNmeaSource.start() requires a sink for the NMEA-0183 strings it would
    // feed a TCP server. This app reads decoded state via the StateFlows, so
    // nobody collects this; DROP_OLDEST keeps it from ever backing up.
    private val throwawaySink = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var source: BleNmeaSource? = null
    private var wakeLock: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        val service: MonitorService get() = this@MonitorService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Guard against duplicate starts (rebind, or START_STICKY relaunch
        // arriving while we're already connected).
        if (_state.value.running) {
            return START_STICKY
        }

        // On a START_STICKY relaunch the OS passes a null intent, so fall
        // back to the persisted device/pin.
        val address = intent?.getStringExtra(EXTRA_ADDRESS) ?: MonitorPrefs.address(this)
        val pin = intent?.getStringExtra(EXTRA_PIN) ?: MonitorPrefs.pin(this)

        if (address.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NmeaMonitorApp.NOTIFICATION_ID, buildNotification())
        acquireWakeLock()

        val ble = BleNmeaSource(this, address, pin) { connected, status ->
            _state.update { it.copy(connected = connected, status = status) }
        }
        source = ble

        // Mirror decoded state into the combined MonitorState. Forward nulls
        // too — the source emits null when a stream goes stale, and the UI
        // relies on that to fall back to "---".
        serviceScope.launch {
            ble.navigationState.collect { nav -> _state.update { it.copy(navigationState = nav) } }
        }
        serviceScope.launch {
            ble.engineState.collect { engine -> _state.update { it.copy(engineState = engine) } }
        }
        serviceScope.launch {
            ble.batteryState.collect { battery -> _state.update { it.copy(batteryState = battery) } }
        }

        // Persist raw engine frames to disk for the temperature graph, and
        // canonicalised BMS slots for the battery graph. FrameLog auto-pads
        // sentinels for skipped seconds, so no ticker is needed here.
        val log = ensureLogger()
        serviceScope.launch {
            ble.rawNavFrames.collect { frame -> log.nav.append(frame) }
        }
        serviceScope.launch {
            ble.rawEngineFrames.collect { frame -> log.engine.append(frame) }
        }
        serviceScope.launch {
            ble.rawBatteryFrames.collect { frame ->
                BmsProtocol.encodeHistorySlot(frame)?.let { log.bms.append(it) }
            }
        }

        serviceScope.launch {
            try {
                ble.start(throwawaySink)
            } catch (e: Exception) {
                _state.update { it.copy(status = "Source error: ${e.message}") }
            }
        }

        _state.update {
            it.copy(running = true, deviceAddress = address)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        source?.stop()
        source = null
        serviceScope.cancel()
        // Close history files after cancelling collectors so the final
        // record is flushed durably.
        logger?.close()
        logger = null
        releaseWakeLock()
        _state.update { MonitorState() }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NmeaMonitorApp.CHANNEL_ID)
            .setContentTitle("NMEA Monitor")
            .setContentText("Monitoring boat data over Bluetooth")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NmeaMonitor::MonitorWakeLock"
        ).apply {
            acquire(/* timeout = */ 24 * 60 * 60 * 1000L) // 24 hours max
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    companion object {
        const val EXTRA_ADDRESS = "ble_address"
        const val EXTRA_PIN = "ble_pin"
    }
}
