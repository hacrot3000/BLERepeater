package com.duongtc.blerepeaterlab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.duongtc.blerepeaterlab.ble.BleEmulatorManager
import com.duongtc.blerepeaterlab.ble.BleScannerManager
import com.duongtc.blerepeaterlab.ble.RealGattCaptureManager
import com.duongtc.blerepeaterlab.model.BleLogEntry
import com.duongtc.blerepeaterlab.model.BleScanItem
import com.duongtc.blerepeaterlab.model.CapturedBleProfile
import com.duongtc.blerepeaterlab.model.LogOperation
import com.duongtc.blerepeaterlab.model.LogSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** ViewModel chính quản lý trạng thái của ứng dụng. */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val scannerManager = BleScannerManager(application)
    private val captureManager = RealGattCaptureManager(application)
    private val emulatorManager = BleEmulatorManager(application)

    val scanResults = scannerManager.scanResults
    val isScanning = scannerManager.isScanning
    val captureState = captureManager.captureState
    val capturedProfile = captureManager.capturedProfile
    val emulatorState = emulatorManager.emulatorState

    private val _logs = MutableStateFlow<List<BleLogEntry>>(emptyList())
    val logs = _logs.asStateFlow()

    fun startScan() {
        scannerManager.startScan()
        addLog(
                BleLogEntry(
                        source = LogSource.SCANNER,
                        operation = LogOperation.SCAN_RESULT,
                        message = "Bắt đầu quét"
                )
        )
    }

    fun stopScan() {
        scannerManager.stopScan()
        addLog(
                BleLogEntry(
                        source = LogSource.SCANNER,
                        operation = LogOperation.SCAN_RESULT,
                        message = "Dừng quét"
                )
        )
    }

    fun connectAndCapture(device: BleScanItem) {
        viewModelScope.launch {
            addLog(
                    BleLogEntry(
                            source = LogSource.REAL_GATT_CLIENT,
                            operation = LogOperation.CONNECT,
                            message = "Kết nối tới ${device.name ?: device.address}"
                    )
            )
            captureManager.connect(device)
        }
    }

    fun disconnectRealDevice() {
        captureManager.disconnect()
        captureManager.close()
        addLog(
                BleLogEntry(
                        source = LogSource.REAL_GATT_CLIENT,
                        operation = LogOperation.DISCONNECT,
                        message = "Ngắt kết nối thiết bị thật"
                )
        )
    }

    fun startEmulator(profile: CapturedBleProfile) {
        viewModelScope.launch {
            addLog(
                    BleLogEntry(
                            source = LogSource.ADVERTISER,
                            operation = LogOperation.START_ADVERTISE,
                            message = "Bắt đầu giả lập"
                    )
            )
            emulatorManager.start(profile)
        }
    }

    fun stopEmulator() {
        emulatorManager.stop()
        addLog(
                BleLogEntry(
                        source = LogSource.ADVERTISER,
                        operation = LogOperation.STOP_ADVERTISE,
                        message = "Dừng giả lập"
                )
        )
    }

    fun addLog(entry: BleLogEntry) {
        val currentLogs = _logs.value.toMutableList()
        currentLogs.add(0, entry) // Thêm vào đầu để log mới nhất ở trên
        _logs.value = currentLogs
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
