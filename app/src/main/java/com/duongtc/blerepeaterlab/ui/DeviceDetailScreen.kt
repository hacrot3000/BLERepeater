package com.duongtc.blerepeaterlab.ui

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
import com.duongtc.blerepeaterlab.model.CapturedGattCharacteristic
import com.duongtc.blerepeaterlab.model.CapturedGattService

/** Màn hình hiển thị chi tiết thiết bị đã capture. */
@Composable
fun DeviceDetailScreen(viewModel: MainViewModel) {
    val profile by viewModel.capturedProfile.collectAsState()
    val captureState by viewModel.captureState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (profile == null) {
            Text("Chưa có thiết bị nào được capture. Trạng thái hiện tại: $captureState")
        } else {
            val p = profile!!
            Text(
                    text = "Thiết bị: ${p.deviceName ?: "Không rõ tên"}",
                    style = MaterialTheme.typography.titleLarge
            )
            Text(text = "Address: ${p.deviceAddress}")

            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Button(
                        onClick = { viewModel.startEmulator(p) },
                        modifier = Modifier.weight(1f).padding(end = 4.dp)
                ) { Text("Start") }

                Button(
                        onClick = { viewModel.exportCapturedProfile() },
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                ) { Text("Export") }

                Button(
                        onClick = { viewModel.disconnectRealDevice() },
                        modifier = Modifier.weight(1f).padding(start = 4.dp)
                ) { Text("Disconnect") }
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(p.services) { service -> ServiceItem(service) }
            }
        }
    }
}

@Composable
fun ServiceItem(service: CapturedGattService) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "Service: ${service.uuid}", style = MaterialTheme.typography.titleMedium)
            service.characteristics.forEach { char -> CharacteristicItem(char) }
        }
    }
}

@Composable
fun CharacteristicItem(char: CapturedGattCharacteristic) {
    Column(modifier = Modifier.padding(start = 16.dp, top = 4.dp)) {
        Text(text = "Char: ${char.uuid}", style = MaterialTheme.typography.bodyMedium)
        Text(
                text = "Properties: ${getPropertiesString(char)}",
                style = MaterialTheme.typography.bodySmall
        )

        val valueStr = char.value?.let { BlePayloadUtils.bytesToHex(it) } ?: "None"
        val utf8Str = BlePayloadUtils.tryUtf8Preview(char.value)

        Text(text = "Value: $valueStr", style = MaterialTheme.typography.bodySmall)
        if (utf8Str != null) {
            Text(text = "UTF-8: $utf8Str", style = MaterialTheme.typography.bodySmall)
        }

        char.descriptors.forEach { desc ->
            Text(
                    text =
                            "  Desc: ${desc.uuid} -> ${desc.value?.let { BlePayloadUtils.bytesToHex(it) } ?: "None"}",
                    style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

fun getPropertiesString(char: CapturedGattCharacteristic): String {
    val props = mutableListOf<String>()
    if (char.canRead) props.add("READ")
    if (char.canWrite) props.add("WRITE")
    if (char.canWriteNoResponse) props.add("WRITE_NO_RESP")
    if (char.canNotify) props.add("NOTIFY")
    if (char.canIndicate) props.add("INDICATE")
    return props.joinToString(", ")
}
