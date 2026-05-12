package com.duongtc.blerepeaterlab

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.duongtc.blerepeaterlab.ui.MainScreen

/** Activity chính của ứng dụng. */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissionsLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result
                ->
                result.forEach { (permission, granted) ->
                    android.util.Log.d("BleRepeaterLab", "Permission $permission granted=$granted")
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestBlePermissionsForDebug()

        setContent {
            Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
            ) { MainScreen(viewModel = viewModel) }
        }
    }

    /**
     * Xin quyền BLE cần thiết.
     *
     * Giai đoạn debug nên xin cả ACCESS_FINE_LOCATION trên mọi Android version, vì nhiều máy vẫn
     * cần bật/cấp Location để BLE scan trả kết quả ổn định.
     */
    private fun requestBlePermissionsForDebug() {
        val permissions =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_ADVERTISE,
                            Manifest.permission.ACCESS_FINE_LOCATION
                    )
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }

        requestPermissionsLauncher.launch(permissions)
    }
}
