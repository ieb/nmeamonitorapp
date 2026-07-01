package uk.co.tfd.nmeamonitor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.core.content.ContextCompat
import uk.co.tfd.nmeamonitor.ui.theme.NmeaMonitorTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MonitorViewModel by viewModels()

    // Action deferred until the BLE permission result comes back.
    private var pendingBleAction: (() -> Unit)? = null

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        val action = pendingBleAction
        pendingBleAction = null
        if (allGranted) {
            action?.invoke()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notification permission is best-effort: proceed either way. The
        // deferred BLE action was stashed before we asked.
        val action = pendingBleAction
        pendingBleAction = null
        action?.invoke()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.loadSettings(this)

        // Auto-connect to the last-used device on subsequent launches.
        if (viewModel.hasSavedDevice() && hasBluetoothPermissions()) {
            viewModel.autoStart(this)
        }

        setContent {
            NmeaMonitorTheme {
                Surface {
                    MonitorApp(
                        viewModel = viewModel,
                        onScan = {
                            requestBlePermissionsThen { viewModel.scanForBleNmeaDevices(this) }
                        },
                        onConnect = { address, pin ->
                            requestPermissionsThen {
                                viewModel.startService(this, address, pin)
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            viewModel.bindService(this)
        } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try {
            viewModel.unbindService(this)
        } catch (_: Exception) {}
    }

    /** Ensure BLE scan/connect permissions, then run [action]. */
    private fun requestBlePermissionsThen(action: () -> Unit) {
        if (hasBluetoothPermissions()) {
            action()
        } else {
            pendingBleAction = action
            bluetoothPermissionLauncher.launch(getBluetoothPermissions())
        }
    }

    /**
     * Ensure both the notification permission (API 33+) and the BLE
     * permissions before running [action] — used by the connect path which
     * starts the foreground service (needs notifications) and connects
     * over BLE.
     */
    private fun requestPermissionsThen(action: () -> Unit) {
        // First the notification permission, then chain into BLE perms.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingBleAction = { requestBlePermissionsThen(action) }
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestBlePermissionsThen(action)
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }
}
