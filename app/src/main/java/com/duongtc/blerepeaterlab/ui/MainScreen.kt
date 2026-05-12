package com.duongtc.blerepeaterlab.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.duongtc.blerepeaterlab.MainViewModel
import com.duongtc.blerepeaterlab.util.PermissionUtils

/**
 * Màn hình chính chứa tabs và xử lý quyền.
 */
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(PermissionUtils.hasPermissions(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
    }

    if (!hasPermissions) {
        PermissionRequiredScreen {
            permissionLauncher.launch(PermissionUtils.getRequiredPermissions())
        }
    } else {
        MainContent(viewModel)
    }
}

@Composable
fun PermissionRequiredScreen(onRequestPermissions: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Ứng dụng cần quyền Bluetooth để hoạt động", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onRequestPermissions, modifier = Modifier.padding(16.dp)) {
                Text("Cấp quyền")
            }
        }
    }
}

@Composable
fun MainContent(viewModel: MainViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Quét", "Chi tiết", "Giả lập")

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }
        
        // Vùng hiển thị nội dung tab
        Box(modifier = Modifier.weight(2f)) {
            when (selectedTab) {
                0 -> ScanScreen(viewModel)
                1 -> DeviceDetailScreen(viewModel)
                2 -> EmulatorScreen(viewModel)
            }
        }
        
        // Vùng hiển thị Log (chiếm 1/3 màn hình)
        Box(modifier = Modifier.weight(1f)) {
            LogPanel(viewModel)
        }
    }
}
