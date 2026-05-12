package com.duongtc.blerepeaterlab.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.duongtc.blerepeaterlab.model.CapturedBleProfile
import com.duongtc.blerepeaterlab.model.EmulatorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Quản lý việc giả lập BLE Peripheral và GATT Server.
 */
class BleEmulatorManager(private val context: Context) {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private val _emulatorState = MutableStateFlow(EmulatorState.IDLE)
    val emulatorState = _emulatorState.asStateFlow()

    // Cache lưu giá trị của các characteristic
    private val characteristicValues = mutableMapOf<String, ByteArray?>()
    private val descriptorValues = mutableMapOf<String, ByteArray?>()

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            Log.d("BleEmulatorManager", "onConnectionStateChange: device=${device.address}, status=$status, newState=$newState")
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                _emulatorState.value = EmulatorState.CLIENT_CONNECTED
                Log.d("BleEmulatorManager", "Client đã kết nối: ${device.address}")
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                _emulatorState.value = EmulatorState.GATT_SERVER_RUNNING // Hoặc ADVERTISING tùy logic
                Log.d("BleEmulatorManager", "Client đã ngắt kết nối: ${device.address}")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            Log.d("BleEmulatorManager", "onCharacteristicReadRequest: ${characteristic.uuid}")
            
            val value = characteristicValues[characteristic.uuid.toString()] ?: byteArrayOf()
            
            if (offset >= value.size) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }
            
            val slicedValue = value.sliceArray(offset until value.size)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slicedValue)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            Log.d("BleEmulatorManager", "onCharacteristicWriteRequest: ${characteristic.uuid}, payload=${BlePayloadUtils.bytesToHex(value)}")
            
            // Lưu lại giá trị mới vào cache
            characteristicValues[characteristic.uuid.toString()] = value
            
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor)
            Log.d("BleEmulatorManager", "onDescriptorReadRequest: ${descriptor.uuid}")
            
            val value = descriptorValues[descriptor.uuid.toString()] ?: byteArrayOf()
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            Log.d("BleEmulatorManager", "onDescriptorWriteRequest: ${descriptor.uuid}, payload=${BlePayloadUtils.bytesToHex(value)}")
            
            descriptorValues[descriptor.uuid.toString()] = value
            
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            Log.d("BleEmulatorManager", "Start advertising thành công")
            _emulatorState.value = EmulatorState.ADVERTISING
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e("BleEmulatorManager", "Start advertising thất bại: $errorCode")
            _emulatorState.value = EmulatorState.ERROR
        }
    }

    @SuppressLint("MissingPermission")
    fun start(profile: CapturedBleProfile) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.e("BleEmulatorManager", "Bluetooth is disabled or not available")
            _emulatorState.value = EmulatorState.ERROR
            return
        }

        _emulatorState.value = EmulatorState.STARTING_ADVERTISER

        // 1. Tạo GATT Server
        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        if (gattServer == null) {
            Log.e("BleEmulatorManager", "Không thể mở GATT Server")
            _emulatorState.value = EmulatorState.ERROR
            return
        }

        // Đổ dữ liệu từ profile vào cache
        for (service in profile.services) {
            val gattService = BluetoothGattService(
                UUID.fromString(service.uuid),
                service.type ?: BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            
            for (char in service.characteristics) {
                val gattChar = BluetoothGattCharacteristic(
                    UUID.fromString(char.uuid),
                    mapProperties(char),
                    mapPermissions(char)
                )
                
                characteristicValues[char.uuid] = char.value
                
                for (desc in char.descriptors) {
                    val gattDesc = BluetoothGattDescriptor(
                        UUID.fromString(desc.uuid),
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                    )
                    descriptorValues[desc.uuid] = desc.value
                    gattChar.addDescriptor(gattDesc)
                }
                gattService.addCharacteristic(gattChar)
            }
            gattServer?.addService(gattService)
        }

        _emulatorState.value = EmulatorState.GATT_SERVER_RUNNING

        // 2. Start Advertising
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e("BleEmulatorManager", "Thiết bị không hỗ trợ BLE advertising")
            _emulatorState.value = EmulatorState.ERROR
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val dataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(true)

        // Lấy service UUID đầu tiên để advertise (hoặc từ profile nếu có)
        val mainServiceUuid = profile.advertiseServiceUuids.firstOrNull() 
            ?: profile.services.firstOrNull()?.uuid
            
        if (mainServiceUuid != null) {
            dataBuilder.addServiceUuid(ParcelUuid(UUID.fromString(mainServiceUuid)))
        }

        // Add manufacturer data nếu có
        profile.manufacturerData.forEach { (id, bytes) ->
            dataBuilder.addManufacturerData(id, bytes)
        }

        val data = dataBuilder.build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        gattServer = null
        _emulatorState.value = EmulatorState.IDLE
        characteristicValues.clear()
        descriptorValues.clear()
        Log.d("BleEmulatorManager", "Emulator stopped")
    }

    private fun mapProperties(char: com.duongtc.blerepeaterlab.model.CapturedGattCharacteristic): Int {
        var props = 0
        if (char.canRead) props = props or BluetoothGattCharacteristic.PROPERTY_READ
        if (char.canWrite) props = props or BluetoothGattCharacteristic.PROPERTY_WRITE
        if (char.canWriteNoResponse) props = props or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        if (char.canNotify) props = props or BluetoothGattCharacteristic.PROPERTY_NOTIFY
        if (char.canIndicate) props = props or BluetoothGattCharacteristic.PROPERTY_INDICATE
        return props
    }

    private fun mapPermissions(char: com.duongtc.blerepeaterlab.model.CapturedGattCharacteristic): Int {
        var perms = 0
        if (char.canRead) perms = perms or BluetoothGattCharacteristic.PERMISSION_READ
        if (char.canWrite || char.canWriteNoResponse) perms = perms or BluetoothGattCharacteristic.PERMISSION_WRITE
        return perms
    }
}
