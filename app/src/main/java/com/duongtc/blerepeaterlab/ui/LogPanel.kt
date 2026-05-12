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
import com.duongtc.blerepeaterlab.model.BleLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Panel hiển thị log real-time.
 */
@Composable
fun LogPanel(viewModel: MainViewModel) {
    val logs by viewModel.logs.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = "Logs hệ thống", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(onClick = { viewModel.clearLogs() }) {
                Text("Xóa Log")
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(logs) { log ->
                LogItemRow(log = log)
            }
        }
    }
}

@Composable
fun LogItemRow(log: BleLogEntry) {
    val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row {
                Text(text = "[${dateFormat.format(Date(log.timestamp))}]", style = MaterialTheme.typography.bodySmall)
                Text(text = " [${log.source}]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(text = " [${log.operation}]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Text(text = log.message, style = MaterialTheme.typography.bodySmall)
            if (log.payloadHex != null) {
                Text(text = "Payload: 0x${log.payloadHex}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
