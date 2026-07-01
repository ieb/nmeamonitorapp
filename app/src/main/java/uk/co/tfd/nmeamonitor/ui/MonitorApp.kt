package uk.co.tfd.nmeamonitor.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import kotlinx.coroutines.launch

private data class MonitorTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(
    MonitorTab("Nav", Icons.Filled.Explore),
    MonitorTab("Engine", Icons.Filled.Speed),
    MonitorTab("Battery", Icons.Filled.BatteryFull),
)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MonitorApp(
    viewModel: MonitorViewModel,
    onScan: () -> Unit,
    onConnect: (address: String, pin: String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var showPicker by remember { mutableStateOf(!viewModel.hasSavedDevice()) }

    // First run (or "Change device") — show the picker full-screen.
    if (showPicker) {
        DevicePickerScreen(
            viewModel = viewModel,
            onScan = onScan,
            onConnect = { address, pin ->
                onConnect(address, pin)
                showPicker = false
            },
        )
        return
    }

    val pagerState = rememberPagerState(pageCount = { TABS.size })
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            ConnectionBar(
                connected = state.connected,
                status = state.status,
                onChangeDevice = { showPicker = true },
            )
        }
    ) { padding ->
        androidx.compose.foundation.layout.Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = pagerState.currentPage) {
                TABS.forEachIndexed { index, tab ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        text = { Text(tab.title) },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> NavigationTiles(state.navigationState, viewModel)
                    1 -> EngineTiles(state.engineState, state.batteryState, viewModel)
                    2 -> BatteryTiles(state.batteryState, viewModel)
                }
            }
        }
    }
}

@Composable
private fun ConnectionBar(
    connected: Boolean,
    status: String?,
    onChangeDevice: () -> Unit,
) {
    val dotColor = when {
        connected -> Color(0xFF2E7D32)                      // green
        status != null && "fail" in status.lowercase() -> Color(0xFFC62828) // red
        status != null -> Color(0xFFF9A825)                 // amber (connecting/authing)
        else -> Color(0xFFC62828)                           // red (disconnected)
    }
    val text = status ?: if (connected) "Connected" else "Disconnected"

    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        // Wrap the button and menu in a Box so the DropdownMenu anchors to
        // the icon on the right, rather than to its own layout slot in the Row.
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Menu")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Change device") },
                    onClick = {
                        menuOpen = false
                        onChangeDevice()
                    }
                )
            }
        }
    }
}
