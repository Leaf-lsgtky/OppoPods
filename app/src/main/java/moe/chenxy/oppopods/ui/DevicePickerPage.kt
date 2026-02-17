package moe.chenxy.oppopods.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import moe.chenxy.oppopods.R

@SuppressLint("MissingPermission")
@Composable
fun DevicePickerPage(onDeviceSelected: (BluetoothDevice) -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    var showMacDialog by remember { mutableStateOf(false) }
    var macInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    if (!hasPermission) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.bt_permission_required))
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }) {
                    Text(stringResource(R.string.grant_permission))
                }
            }
        }
        return
    }

    val btManager = context.getSystemService(BluetoothManager::class.java)
    val adapter = btManager?.adapter
    val pairedDevices = remember(hasPermission) {
        adapter?.bondedDevices?.toList()?.sortedByDescending {
            it.name?.contains("oppo", ignoreCase = true) == true
        } ?: emptyList()
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(12.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.select_device),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            if (pairedDevices.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_paired_devices),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(pairedDevices, key = { it.address }) { device ->
                val isOppo = device.name?.contains("oppo", ignoreCase = true) == true
                ListItem(
                    headlineContent = {
                        Text(device.name ?: stringResource(R.string.unknown_device))
                    },
                    supportingContent = { Text(device.address) },
                    leadingContent = {
                        Icon(
                            Icons.Default.Bluetooth,
                            contentDescription = null,
                            tint = if (isOppo) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.clickable { onDeviceSelected(device) }
                )
                HorizontalDivider()
            }
        }

        OutlinedButton(
            onClick = { showMacDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Icon(Icons.Default.Edit, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.input_mac_manually))
        }
    }

    if (showMacDialog) {
        AlertDialog(
            onDismissRequest = { showMacDialog = false },
            title = { Text(stringResource(R.string.input_mac_title)) },
            text = {
                OutlinedTextField(
                    value = macInput,
                    onValueChange = { macInput = it.uppercase() },
                    label = { Text("MAC") },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val mac = macInput.trim()
                        if (BluetoothAdapter.checkBluetoothAddress(mac)) {
                            val device = adapter?.getRemoteDevice(mac)
                            if (device != null) {
                                showMacDialog = false
                                onDeviceSelected(device)
                            }
                        }
                    }
                ) { Text(stringResource(R.string.connect)) }
            },
            dismissButton = {
                TextButton(onClick = { showMacDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
