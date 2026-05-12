package com.duongtc.blerepeaterlab.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.duongtc.blerepeaterlab.model.BleScanItem

/** Quản lý việc quét các thiết bị BLE. */
class BleScannerManager(private val context: Context) {

    private val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var scanner: BluetoothLeScanner? = null

    private val _scanResults = MutableStateFlow<List<BleScanItem>>(emptyList())
    val scanResults = _scanResults.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val scanCallback =
            object : ScanCallback() {
                @SuppressLint("MissingPermission")
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    super.onScanResult(callbackType, result)

                    Log.d(
                            "BleScannerManager",
                            "Found BLE: address=${result.device.address}, " +
                                    "rssi=${result.rssi}, " +
                                    "name=${result.scanRecord?.deviceName}, " +
                                    "services=${result.scanRecord?.serviceUuids}, " +
                                    "raw=${result.scanRecord?.bytes?.let { BlePayloadUtils.bytesToHex(it) }}"
                    )

                    // Gọi filter
                    if (!TargetDeviceFilter.isTargetDevice(result)) {
                        return
                    }

                    val device = result.device
                    val scanRecord = result.scanRecord

                    val serviceUuids =
                            scanRecord?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
                    val manufacturerData =
                            BlePayloadUtils.sparseArrayToMap(scanRecord?.manufacturerSpecificData)
                    val serviceData =
                            scanRecord?.serviceData?.mapKeys { it.key.uuid.toString() }
                                    ?: emptyMap()

                    val newItem =
                            BleScanItem(
                                    id = device.address,
                                    name = scanRecord?.deviceName ?: device.name,
                                    address = device.address,
                                    rssi = result.rssi,
                                    serviceUuids = serviceUuids,
                                    manufacturerData = manufacturerData,
                                    serviceData = serviceData,
                                    rawAdvertiseHex =
                                            scanRecord?.bytes?.let {
                                                BlePayloadUtils.bytesToHex(it)
                                            },
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

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            false
        }
    }

    private fun logBleDebugState() {
        Log.d("BleScannerManager", "Android SDK=${Build.VERSION.SDK_INT}")
        Log.d(
                "BleScannerManager",
                "BLE supported=${context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)}"
        )
        Log.d("BleScannerManager", "Bluetooth adapter=$bluetoothAdapter")
        Log.d("BleScannerManager", "Bluetooth enabled=${bluetoothAdapter?.isEnabled}")
        Log.d("BleScannerManager", "Location enabled=${isLocationEnabled()}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d(
                    "BleScannerManager",
                    "BLUETOOTH_SCAN=${hasPermission(Manifest.permission.BLUETOOTH_SCAN)}"
            )
            Log.d(
                    "BleScannerManager",
                    "BLUETOOTH_CONNECT=${hasPermission(Manifest.permission.BLUETOOTH_CONNECT)}"
            )
            Log.d(
                    "BleScannerManager",
                    "BLUETOOTH_ADVERTISE=${hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)}"
            )
        }

        Log.d(
                "BleScannerManager",
                "ACCESS_FINE_LOCATION=${hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)}"
        )
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
        logBleDebugState()

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e("BleScannerManager", "Bluetooth is disabled or not available")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                Log.e("BleScannerManager", "Missing BLUETOOTH_SCAN permission")
                return
            }

            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                Log.e("BleScannerManager", "Missing BLUETOOTH_CONNECT permission")
                return
            }
        } else {
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                Log.e("BleScannerManager", "Missing ACCESS_FINE_LOCATION permission")
                return
            }
        }

        scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            Log.e("BleScannerManager", "Scanner not available")
            return
        }

        val settings =
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        _scanResults.value = emptyList()

        try {
            scanner?.startScan(null, settings, scanCallback)
            _isScanning.value = true
            Log.d("BleScannerManager", "Scan started")
        } catch (e: SecurityException) {
            Log.e("BleScannerManager", "Start scan failed because permission is missing", e)
            _isScanning.value = false
        } catch (e: Exception) {
            Log.e("BleScannerManager", "Start scan failed", e)
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanner?.stopScan(scanCallback)
        _isScanning.value = false
        Log.d("BleScannerManager", "Scan stopped")
    }
}
