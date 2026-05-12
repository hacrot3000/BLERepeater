package com.duongtc.blerepeaterlab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.duongtc.blerepeaterlab.MainViewModel
import com.duongtc.blerepeaterlab.ble.BlePayloadUtils
import com.duongtc.blerepeaterlab.model.BleScanItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Màn hình quét thiết bị BLE.
 */
@Composable
fun ScanScreen(viewModel: MainViewModel) {
    val scanResults by viewModel.scanResults.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { viewModel.startScan() },
                enabled = !isScanning,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                Text("Start Scan")
            }
            Button(
                onClick = { viewModel.stopScan() },
                enabled = isScanning,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            ) {
                Text("Stop Scan")
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(top = 16.dp)) {
            items(scanResults) { item ->
                ScanItemRow(item = item) {
                    viewModel.stopScan()
                    viewModel.connectAndCapture(item)
                }
            }
        }
    }
}

@Composable
fun ScanItemRow(item: BleScanItem, onClick: () -> Unit) {
    val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = item.name ?: "Không rõ tên", style = MaterialTheme.typography.titleMedium)
            Text(text = "Address: ${item.address}")
            Text(text = "RSSI: ${item.rssi} dBm")
            Text(text = "Lần đầu: ${dateFormat.format(Date(item.firstSeenAt))}")
            Text(text = "Lần cuối: ${dateFormat.format(Date(item.lastSeenAt))}")
            
            if (item.serviceUuids.isNotEmpty()) {
                Text(text = "Services: ${item.serviceUuids.size} UUIDs")
            }
            
            val mfgDataStr = BlePayloadUtils.parseManufacturerData(item.manufacturerData)
            if (mfgDataStr != "None") {
                Text(text = "Mfg Data: $mfgDataStr", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
