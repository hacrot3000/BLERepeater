package com.duongtc.blerepeaterlab.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.duongtc.blerepeaterlab.model.BleScanItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Quản lý việc quét các thiết bị BLE.
 */
class BleScannerManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null

    private val _scanResults = MutableStateFlow<List<BleScanItem>>(emptyList())
    val scanResults = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            
            // Gọi filter
            if (!TargetDeviceFilter.isTargetDevice(result)) {
                return
            }

            val device = result.device
            val scanRecord = result.scanRecord
            
            val serviceUuids = scanRecord?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
            val manufacturerData = BlePayloadUtils.sparseArrayToMap(scanRecord?.manufacturerSpecificData)
            val serviceData = scanRecord?.serviceData?.mapKeys { it.key.uuid.toString() } ?: emptyMap()
            
            val newItem = BleScanItem(
                id = device.address,
                name = scanRecord?.deviceName ?: device.name,
                address = device.address,
                rssi = result.rssi,
                serviceUuids = serviceUuids,
                manufacturerData = manufacturerData,
                serviceData = serviceData,
                rawAdvertiseHex = scanRecord?.bytes?.let { BlePayloadUtils.bytesToHex(it) },
                lastSeenAt = System.currentTimeMillis()
            )

            updateScanResults(newItem)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e("BleScannerManager", "Scan failed with error code: $errorCode")
            _isScanning.value = false
        }
    }

    private fun updateScanResults(newItem: BleScanItem) {
        val currentList = _scanResults.value.toMutableList()
        val index = currentList.indexOfFirst { it.address == newItem.address }
        if (index != -1) {
            // Cập nhật thiết bị đã có
            val oldItem = currentList[index]
            currentList[index] = newItem.copy(firstSeenAt = oldItem.firstSeenAt)
        } else {
            // Thêm thiết bị mới
            currentList.add(newItem.copy(firstSeenAt = newItem.lastSeenAt))
        }
        _scanResults.value = currentList
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e("BleScannerManager", "Bluetooth is disabled or not available")
            return
        }

        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e("BleScannerManager", "Scanner not available")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        _scanResults.value = emptyList() // Xóa kết quả cũ
        scanner?.startScan(null, settings, scanCallback)
        _isScanning.value = true
        Log.d("BleScannerManager", "Scan started")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanner?.stopScan(scanCallback)
        _isScanning.value = false
        Log.d("BleScannerManager", "Scan stopped")
    }
}
