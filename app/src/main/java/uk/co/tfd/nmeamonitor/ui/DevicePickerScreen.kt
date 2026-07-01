package uk.co.tfd.nmeamonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * First-run (and "Change device") screen: scan for BLE devices advertising
 * the nav service, let the user set the PIN, and tap a device to connect.
 */
@Composable
fun DevicePickerScreen(
    viewModel: MonitorViewModel,
    onScan: () -> Unit,
    onConnect: (address: String, pin: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scanning by viewModel.bleScanning.collectAsState()
    val devices by viewModel.bleScannedDevices.collectAsState()
    val pin by viewModel.blePin.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Connect to boat",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Scan for the boat's Bluetooth device, then tap it to connect. " +
                "The PIN is needed to view battery and engine data.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = pin,
            onValueChange = { new -> viewModel.setBlePin(new.take(4).filter { it.isDigit() }) },
            label = { Text("PIN") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onScan, enabled = !scanning) {
                Text(if (scanning) "Scanning…" else "Scan")
            }
            if (scanning) {
                CircularProgressIndicator(modifier = Modifier.padding(4.dp))
            }
        }

        if (devices.isEmpty() && !scanning) {
            Text(
                text = "No devices found yet. Tap Scan.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(devices) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConnect(device.address, pin) }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(device.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            device.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
