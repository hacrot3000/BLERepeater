package com.duongtc.blerepeaterlab.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.duongtc.blerepeaterlab.MainViewModel
import com.duongtc.blerepeaterlab.model.EmulatorState

/**
 * Màn hình điều khiển giả lập.
 */
@Composable
fun EmulatorScreen(viewModel: MainViewModel) {
    val emulatorState by viewModel.emulatorState.collectAsState()
    val profile by viewModel.capturedProfile.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Trạng thái Emulator: $emulatorState", style = MaterialTheme.typography.titleLarge)
        
        if (profile != null) {
            Text(text = "Đang giả lập cho: ${profile!!.deviceName ?: "Không rõ tên"}")
            Text(text = "MAC gốc: ${profile!!.deviceAddress}")
        } else {
            Text(text = "Chưa có profile để giả lập. Hãy capture một thiết bị ở màn hình Quét.")
        }

        Button(
            onClick = { viewModel.stopEmulator() },
            enabled = emulatorState != EmulatorState.IDLE,
            modifier = Modifier.padding(top = 16.dp)
        ) {
            Text("Stop Emulator")
        }
        
        Text(
            text = "Lưu ý: Android không cho phép đổi địa chỉ MAC Bluetooth của máy thành MAC của thiết bị khác.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 32.dp)
        )
    }
}
