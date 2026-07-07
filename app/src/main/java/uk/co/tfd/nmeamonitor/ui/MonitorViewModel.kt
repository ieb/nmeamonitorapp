package uk.co.tfd.nmeamonitor.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import uk.co.tfd.nmeamonitor.bluetooth.BleNmeaSource
import uk.co.tfd.nmeamonitor.service.MonitorPrefs
import uk.co.tfd.nmeamonitor.service.MonitorService
import uk.co.tfd.nmeamonitor.service.MonitorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class BleScannedDevice(
    val name: String,
    val address: String
)

class MonitorViewModel : ViewModel() {

    companion object {
        private val NAV_SERVICE_PARCEL = ParcelUuid(BleNmeaSource.NMEA_SERVICE_UUID)
        private val BW_SERVICE_PARCEL  = ParcelUuid(BleNmeaSource.BW_SERVICE_UUID)
        private const val SCAN_DURATION_MS = 8_000L

        const val DEFAULT_CHART_WINDOW_MS = 10L * 60 * 1000   // 10 min
        private const val MIN_CHART_WINDOW_MS = 30L * 1000    // 30 s floor for panning math
    }

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val _bleAddress = MutableStateFlow("")
    val bleAddress: StateFlow<String> = _bleAddress.asStateFlow()

    private val _blePin = MutableStateFlow("0000")
    val blePin: StateFlow<String> = _blePin.asStateFlow()

    private val _bleScannedDevices = MutableStateFlow<List<BleScannedDevice>>(emptyList())
    val bleScannedDevices: StateFlow<List<BleScannedDevice>> = _bleScannedDevices.asStateFlow()

    private val _bleScanning = MutableStateFlow(false)
    val bleScanning: StateFlow<Boolean> = _bleScanning.asStateFlow()

    // Directory holding engine history files; set once the service binds
    // so the Engine chart can build a HistoryDataSource. null until bound.
    private val _historyDir = MutableStateFlow<File?>(null)
    val historyDir: StateFlow<File?> = _historyDir.asStateFlow()

    // --- Engine temperature chart window state -------------------------
    // chartWindowMs: visible span. chartEndMs: right edge (null = live tail,
    // follows newest data). chartCrosshairMs: pinned cursor (null = none).
    private val _chartWindowMs = MutableStateFlow(DEFAULT_CHART_WINDOW_MS)
    val chartWindowMs: StateFlow<Long> = _chartWindowMs.asStateFlow()

    private val _chartEndMs = MutableStateFlow<Long?>(null)
    val chartEndMs: StateFlow<Long?> = _chartEndMs.asStateFlow()

    private val _chartCrosshairMs = MutableStateFlow<Long?>(null)
    val chartCrosshairMs: StateFlow<Long?> = _chartCrosshairMs.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val seenBleAddresses = mutableSetOf<String>()

    private var service: MonitorService? = null
    private var bound = false

    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            if (address in seenBleAddresses) return

            val deviceName = try { result.device.name } catch (_: Exception) { null }
            // Firmware advertises the BoatWatch service (AA00); the Nav service (FF00)
            // is present after connect but not in the advertisement, so we accept
            // either UUID (defensive, in case the firmware advertises FF00 later).
            val advertisedUuids = result.scanRecord?.serviceUuids
            val advertisesBridge =
                advertisedUuids?.contains(BW_SERVICE_PARCEL) == true ||
                advertisedUuids?.contains(NAV_SERVICE_PARCEL) == true

            // Only show devices that advertise our service or have a name
            if (!advertisesBridge && deviceName == null) return

            seenBleAddresses.add(address)
            val name = deviceName ?: "BLE Nav"
            handler.post {
                _bleScannedDevices.value = _bleScannedDevices.value + BleScannedDevice(name, address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post { _bleScanning.value = false }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as MonitorService.LocalBinder).service
            service = svc
            bound = true
            _historyDir.value = svc.historyDir
            viewModelScope.launch {
                svc.state.collect { _state.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            _historyDir.value = null
        }
    }

    fun loadSettings(context: Context) {
        _bleAddress.value = MonitorPrefs.address(context)
        _blePin.value = MonitorPrefs.pin(context)
    }

    fun hasSavedDevice(): Boolean = _bleAddress.value.isNotBlank()

    fun setBlePin(pin: String) {
        _blePin.value = pin
    }

    fun bindService(context: Context) {
        val intent = Intent(context, MonitorService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService(context: Context) {
        if (bound) {
            context.unbindService(connection)
            bound = false
            service = null
        }
    }

    @SuppressLint("MissingPermission")
    fun scanForBleNmeaDevices(context: Context) {
        if (_bleScanning.value) return

        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val scanner = btManager?.adapter?.bluetoothLeScanner ?: return

        seenBleAddresses.clear()
        _bleScannedDevices.value = emptyList()
        _bleScanning.value = true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, bleScanCallback)

        handler.postDelayed({
            try { scanner.stopScan(bleScanCallback) } catch (_: Exception) {}
            _bleScanning.value = false
        }, SCAN_DURATION_MS)
    }

    /**
     * Persist the chosen device + pin, (re)start the foreground service on it,
     * and bind so the UI receives state. Used on first-run device pick and
     * from the "Change device" menu.
     */
    fun startService(context: Context, address: String, pin: String) {
        _bleAddress.value = address
        _blePin.value = pin
        MonitorPrefs.save(context, address, pin)
        val intent = Intent(context, MonitorService::class.java).apply {
            putExtra(MonitorService.EXTRA_ADDRESS, address)
            putExtra(MonitorService.EXTRA_PIN, pin)
        }
        ContextCompat.startForegroundService(context, intent)
        bindService(context)
    }

    /** Auto-start on launch when a device is already saved. */
    fun autoStart(context: Context) {
        if (hasSavedDevice()) {
            startService(context, _bleAddress.value, _blePin.value)
        }
    }

    // --- Engine chart controls -----------------------------------------

    /** Set the visible time span (e.g. 1m / 10m / 1h presets). */
    fun setChartWindow(ms: Long) {
        _chartWindowMs.value = ms.coerceAtLeast(MIN_CHART_WINDOW_MS)
    }

    /**
     * Shift the window's right edge by [deltaMs] (negative pans into the
     * past). Panning to or past "now" snaps back to the live tail
     * (chartEndMs = null) so the chart resumes following new data.
     */
    fun panChartBy(deltaMs: Long, nowMs: Long) {
        val curEnd = _chartEndMs.value ?: nowMs
        val next = curEnd + deltaMs
        _chartEndMs.value = if (next >= nowMs) null else next
    }

    /** Snap back to the live tail and clear any pinned crosshair. */
    fun snapChartLive() {
        _chartEndMs.value = null
        _chartCrosshairMs.value = null
    }

    fun setChartCrosshair(ms: Long?) {
        _chartCrosshairMs.value = ms
    }

    fun stopService(context: Context) {
        unbindService(context)
        context.stopService(Intent(context, MonitorService::class.java))
        _state.value = MonitorState()
    }
}
