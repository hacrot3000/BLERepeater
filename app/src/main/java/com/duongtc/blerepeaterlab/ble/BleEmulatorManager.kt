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
import com.duongtc.blerepeaterlab.model.CapturedGattCharacteristic
import com.duongtc.blerepeaterlab.model.EmulatorState
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Quản lý việc giả lập BLE Peripheral và GATT Server. */
class BleEmulatorManager(private val context: Context) {

    private val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private val _emulatorState = MutableStateFlow(EmulatorState.IDLE)
    val emulatorState = _emulatorState.asStateFlow()

    /**
     * Cache giá trị characteristic.
     *
     * Key dùng dạng: serviceUuid|characteristicUuid
     *
     * Không dùng riêng characteristicUuid vì một số thiết bị có thể có characteristic UUID trùng ở
     * nhiều service khác nhau.
     */
    private val characteristicValues = mutableMapOf<String, ByteArray?>()

    /**
     * Cache giá trị descriptor.
     *
     * Key dùng dạng: serviceUuid|characteristicUuid|descriptorUuid
     *
     * Không dùng riêng descriptorUuid vì descriptor như CCCD 00002902-0000-1000-8000-00805f9b34fb
     * thường xuất hiện ở rất nhiều characteristic, nếu key chỉ là descriptor UUID sẽ bị đè dữ liệu.
     */
    private val descriptorValues = mutableMapOf<String, ByteArray?>()

    /**
     * Lưu trạng thái CCCD theo từng client + từng descriptor.
     *
     * Key dùng dạng: deviceAddress|serviceUuid|characteristicUuid|descriptorUuid
     */
    private val clientDescriptorValues = mutableMapOf<String, ByteArray>()

    private val serverCallback =
            object : BluetoothGattServerCallback() {

                override fun onConnectionStateChange(
                        device: BluetoothDevice,
                        status: Int,
                        newState: Int
                ) {
                    super.onConnectionStateChange(device, status, newState)

                    Log.d(
                            TAG,
                            "onConnectionStateChange: device=${device.address}, status=$status, newState=$newState"
                    )

                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        _emulatorState.value = EmulatorState.CLIENT_CONNECTED
                        Log.d(TAG, "Client đã kết nối: ${device.address}")
                    } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                        _emulatorState.value = EmulatorState.GATT_SERVER_RUNNING
                        Log.d(TAG, "Client đã ngắt kết nối: ${device.address}")
                    }
                }

                @SuppressLint("MissingPermission")
                override fun onCharacteristicReadRequest(
                        device: BluetoothDevice,
                        requestId: Int,
                        offset: Int,
                        characteristic: BluetoothGattCharacteristic
                ) {
                    super.onCharacteristicReadRequest(device, requestId, offset, characteristic)

                    val key = characteristicKey(characteristic)
                    val fullValue = characteristicValues[key] ?: byteArrayOf()

                    Log.d(
                            TAG,
                            "onCharacteristicReadRequest: key=$key, offset=$offset, value=${BlePayloadUtils.bytesToHex(fullValue)}"
                    )

                    if (offset > fullValue.size) {
                        gattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_INVALID_OFFSET,
                                offset,
                                null
                        )
                        return
                    }

                    val slicedValue =
                            if (offset == fullValue.size) {
                                byteArrayOf()
                            } else {
                                fullValue.sliceArray(offset until fullValue.size)
                            }

                    gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            offset,
                            slicedValue
                    )
                }

                @SuppressLint("MissingPermission")
                override fun onCharacteristicWriteRequest(
                        device: BluetoothDevice,
                        requestId: Int,
                        characteristic: BluetoothGattCharacteristic,
                        preparedWrite: Boolean,
                        responseNeeded: Boolean,
                        offset: Int,
                        value: ByteArray
                ) {
                    super.onCharacteristicWriteRequest(
                            device,
                            requestId,
                            characteristic,
                            preparedWrite,
                            responseNeeded,
                            offset,
                            value
                    )

                    val key = characteristicKey(characteristic)

                    Log.d(
                            TAG,
                            "onCharacteristicWriteRequest: key=$key, preparedWrite=$preparedWrite, responseNeeded=$responseNeeded, offset=$offset, payload=${BlePayloadUtils.bytesToHex(value)}"
                    )

                    val oldValue = characteristicValues[key] ?: byteArrayOf()
                    val newValue =
                            if (offset <= 0) {
                                value
                            } else {
                                mergeWriteValue(oldValue, offset, value)
                            }

                    // Giai đoạn hiện tại chỉ cache lại dữ liệu client ghi vào.
                    // TODO: Sau này bridge payload này sang thiết bị BLE thật nếu cần proxy
                    // realtime.
                    characteristicValues[key] = newValue

                    if (responseNeeded) {
                        gattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                value
                        )
                    }
                }

                @SuppressLint("MissingPermission")
                override fun onDescriptorReadRequest(
                        device: BluetoothDevice,
                        requestId: Int,
                        offset: Int,
                        descriptor: BluetoothGattDescriptor
                ) {
                    super.onDescriptorReadRequest(device, requestId, offset, descriptor)

                    val baseKey = descriptorKey(descriptor)
                    val clientKey = clientDescriptorKey(device, descriptor)

                    val fullValue =
                            clientDescriptorValues[clientKey]
                                    ?: descriptorValues[baseKey]
                                            ?: defaultDescriptorValue(descriptor)

                    Log.d(
                            TAG,
                            "onDescriptorReadRequest: key=$baseKey, client=${device.address}, offset=$offset, value=${BlePayloadUtils.bytesToHex(fullValue)}"
                    )

                    if (offset > fullValue.size) {
                        gattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_INVALID_OFFSET,
                                offset,
                                null
                        )
                        return
                    }

                    val slicedValue =
                            if (offset == fullValue.size) {
                                byteArrayOf()
                            } else {
                                fullValue.sliceArray(offset until fullValue.size)
                            }

                    gattServer?.sendResponse(
                            device,
                            requestId,
                            BluetoothGatt.GATT_SUCCESS,
                            offset,
                            slicedValue
                    )
                }

                @SuppressLint("MissingPermission")
                override fun onDescriptorWriteRequest(
                        device: BluetoothDevice,
                        requestId: Int,
                        descriptor: BluetoothGattDescriptor,
                        preparedWrite: Boolean,
                        responseNeeded: Boolean,
                        offset: Int,
                        value: ByteArray
                ) {
                    super.onDescriptorWriteRequest(
                            device,
                            requestId,
                            descriptor,
                            preparedWrite,
                            responseNeeded,
                            offset,
                            value
                    )

                    val baseKey = descriptorKey(descriptor)
                    val clientKey = clientDescriptorKey(device, descriptor)

                    Log.d(
                            TAG,
                            "onDescriptorWriteRequest: key=$baseKey, client=${device.address}, preparedWrite=$preparedWrite, responseNeeded=$responseNeeded, offset=$offset, payload=${BlePayloadUtils.bytesToHex(value)}"
                    )

                    val oldValue =
                            clientDescriptorValues[clientKey]
                                    ?: descriptorValues[baseKey] ?: byteArrayOf()

                    val newValue =
                            if (offset <= 0) {
                                value
                            } else {
                                mergeWriteValue(oldValue, offset, value)
                            }

                    clientDescriptorValues[clientKey] = newValue

                    // Nếu là CCCD thì log rõ client đang bật notify/indicate hay tắt.
                    if (descriptor.uuid == UUID_CCCD) {
                        Log.d(
                                TAG,
                                "CCCD updated: client=${device.address}, value=${describeCccdValue(newValue)}"
                        )
                    }

                    if (responseNeeded) {
                        gattServer?.sendResponse(
                                device,
                                requestId,
                                BluetoothGatt.GATT_SUCCESS,
                                offset,
                                value
                        )
                    }
                }
            }

    private val advertiseCallback =
            object : AdvertiseCallback() {

                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    super.onStartSuccess(settingsInEffect)

                    Log.d(TAG, "Start advertising thành công")
                    _emulatorState.value = EmulatorState.ADVERTISING
                }

                override fun onStartFailure(errorCode: Int) {
                    super.onStartFailure(errorCode)

                    val reason =
                            when (errorCode) {
                                ADVERTISE_FAILED_DATA_TOO_LARGE -> "ADVERTISE_FAILED_DATA_TOO_LARGE"
                                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                                        "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS"
                                ADVERTISE_FAILED_ALREADY_STARTED ->
                                        "ADVERTISE_FAILED_ALREADY_STARTED"
                                ADVERTISE_FAILED_INTERNAL_ERROR -> "ADVERTISE_FAILED_INTERNAL_ERROR"
                                ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                                        "ADVERTISE_FAILED_FEATURE_UNSUPPORTED"
                                else -> "UNKNOWN"
                            }

                    Log.e(TAG, "Start advertising thất bại: $errorCode / $reason")
                    _emulatorState.value = EmulatorState.ERROR
                }
            }

    @SuppressLint("MissingPermission")
    fun start(profile: CapturedBleProfile) {
        stop()

        if (bluetoothAdapter == null) {
            Log.e(TAG, "Bluetooth adapter null")
            _emulatorState.value = EmulatorState.ERROR
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.e(TAG, "Bluetooth chưa bật")
            _emulatorState.value = EmulatorState.ERROR
            return
        }

        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Log.e(TAG, "Thiết bị Android này không hỗ trợ BLE advertising")
            _emulatorState.value = EmulatorState.ERROR
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "BluetoothLeAdvertiser null")
            _emulatorState.value = EmulatorState.ERROR
            return
        }

        gattServer = bluetoothManager.openGattServer(context, serverCallback)
        if (gattServer == null) {
            Log.e(TAG, "Không mở được BluetoothGattServer")
            _emulatorState.value = EmulatorState.ERROR
            return
        }

        characteristicValues.clear()
        descriptorValues.clear()
        clientDescriptorValues.clear()

        createGattDatabase(profile)

        val mainServiceUuid =
                profile.advertiseServiceUuids.firstOrNull() ?: profile.services.firstOrNull()?.uuid

        Log.d(TAG, "Profile deviceName=${profile.deviceName}")
        Log.d(TAG, "Profile advertiseServiceUuids=${profile.advertiseServiceUuids}")
        Log.d(TAG, "Profile manufacturerData count=${profile.manufacturerData.size}")

        if (mainServiceUuid.isNullOrBlank()) {
            Log.e(TAG, "Không có service UUID nào để advertise")
            _emulatorState.value = EmulatorState.ERROR
            return
        }

        trySetBluetoothName(profile.deviceName)

        val settings =
                AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                        .setConnectable(true)
                        .setTimeout(0)
                        .build()

        val advertiseDataBuilder =
                AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)

        Log.d(TAG, "Advertise main service UUID=$mainServiceUuid")
        advertiseDataBuilder.addServiceUuid(ParcelUuid(UUID.fromString(mainServiceUuid)))

        /*
         * Ưu tiên để Service UUID ở advertising data chính để app gốc có filter theo Service UUID
         * được hệ điều hành trả callback.
         *
         * Device name + manufacturerData đưa sang scan response để giảm nguy cơ quá 31 bytes.
         */
        val scanResponseBuilder =
                AdvertiseData.Builder()
                        .setIncludeDeviceName(!profile.deviceName.isNullOrBlank())
                        .setIncludeTxPowerLevel(false)

        profile.manufacturerData.forEach { (id, bytes) ->
            Log.d(TAG, "Add manufacturerData id=$id, hex=${BlePayloadUtils.bytesToHex(bytes)}")
            scanResponseBuilder.addManufacturerData(id, bytes)
        }

        val advertiseData: AdvertiseData
        val scanResponse: AdvertiseData

        try {
            advertiseData = advertiseDataBuilder.build()
            scanResponse = scanResponseBuilder.build()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Build AdvertiseData lỗi. Có thể dữ liệu advertise/scanResponse quá lớn.", e)
            _emulatorState.value = EmulatorState.ERROR
            return
        }

        _emulatorState.value = EmulatorState.STARTING_ADVERTISER

        try {
            advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Start advertising lỗi do thiếu quyền BLUETOOTH_ADVERTISE/CONNECT", e)
            _emulatorState.value = EmulatorState.ERROR
        } catch (e: Exception) {
            Log.e(TAG, "Start advertising lỗi", e)
            _emulatorState.value = EmulatorState.ERROR
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Stop advertising lỗi", e)
        }

        advertiser = null

        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Close GATT server lỗi", e)
        }

        gattServer = null

        characteristicValues.clear()
        descriptorValues.clear()
        clientDescriptorValues.clear()

        _emulatorState.value = EmulatorState.IDLE
        Log.d(TAG, "Emulator stopped")
    }

    @SuppressLint("MissingPermission")
    private fun createGattDatabase(profile: CapturedBleProfile) {
        profile.services.forEach { capturedService ->
            val serviceUuid = UUID.fromString(capturedService.uuid)

            val service =
                    BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

            capturedService.characteristics.forEach { capturedChar ->
                val characteristicUuid = UUID.fromString(capturedChar.uuid)

                val characteristic =
                        BluetoothGattCharacteristic(
                                characteristicUuid,
                                mapProperties(capturedChar),
                                mapPermissions(capturedChar)
                        )

                val charKey = characteristicKey(capturedService.uuid, capturedChar.uuid)
                characteristicValues[charKey] = capturedChar.value

                capturedChar.descriptors.forEach { capturedDesc ->
                    val descriptorUuid = UUID.fromString(capturedDesc.uuid)

                    val descriptor =
                            BluetoothGattDescriptor(
                                    descriptorUuid,
                                    BluetoothGattDescriptor.PERMISSION_READ or
                                            BluetoothGattDescriptor.PERMISSION_WRITE
                            )

                    val descKey =
                            descriptorKey(
                                    capturedService.uuid,
                                    capturedChar.uuid,
                                    capturedDesc.uuid
                            )

                    descriptorValues[descKey] = capturedDesc.value
                    characteristic.addDescriptor(descriptor)
                }

                ensureCccdIfNeeded(
                        serviceUuid = capturedService.uuid,
                        capturedChar = capturedChar,
                        characteristic = characteristic
                )

                val added = service.addCharacteristic(characteristic)
                Log.d(
                        TAG,
                        "Add characteristic service=${capturedService.uuid}, char=${capturedChar.uuid}, added=$added"
                )
            }

            val ok = gattServer?.addService(service)
            Log.d(TAG, "Add GATT service ${capturedService.uuid}, ok=$ok")
        }

        _emulatorState.value = EmulatorState.GATT_SERVER_RUNNING
    }

    private fun ensureCccdIfNeeded(
            serviceUuid: String,
            capturedChar: CapturedGattCharacteristic,
            characteristic: BluetoothGattCharacteristic
    ) {
        if (!capturedChar.canNotify && !capturedChar.canIndicate) {
            return
        }

        val hasCccd = characteristic.descriptors.any { it.uuid == UUID_CCCD }
        if (hasCccd) {
            return
        }

        val cccd =
                BluetoothGattDescriptor(
                        UUID_CCCD,
                        BluetoothGattDescriptor.PERMISSION_READ or
                                BluetoothGattDescriptor.PERMISSION_WRITE
                )

        val descKey = descriptorKey(serviceUuid, capturedChar.uuid, UUID_CCCD.toString())

        descriptorValues[descKey] = byteArrayOf(0x00, 0x00)
        characteristic.addDescriptor(cccd)

        Log.d(TAG, "Auto add CCCD for service=$serviceUuid, char=${capturedChar.uuid}")
    }

    private fun mapProperties(char: CapturedGattCharacteristic): Int {
        var props = 0

        if (char.canRead) {
            props = props or BluetoothGattCharacteristic.PROPERTY_READ
        }

        if (char.canWrite) {
            props = props or BluetoothGattCharacteristic.PROPERTY_WRITE
        }

        if (char.canWriteNoResponse) {
            props = props or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        }

        if (char.canNotify) {
            props = props or BluetoothGattCharacteristic.PROPERTY_NOTIFY
        }

        if (char.canIndicate) {
            props = props or BluetoothGattCharacteristic.PROPERTY_INDICATE
        }

        return props
    }

    private fun mapPermissions(char: CapturedGattCharacteristic): Int {
        var perms = 0

        if (char.canRead || char.canNotify || char.canIndicate) {
            perms = perms or BluetoothGattCharacteristic.PERMISSION_READ
        }

        if (char.canWrite || char.canWriteNoResponse) {
            perms = perms or BluetoothGattCharacteristic.PERMISSION_WRITE
        }

        /*
         * Nếu characteristic không có permission nào, một số app client vẫn có thể cần read.
         * Gán READ mặc định để tránh characteristic bị quá "câm" ở GATT server giả lập.
         */
        if (perms == 0) {
            perms = BluetoothGattCharacteristic.PERMISSION_READ
        }

        return perms
    }

    private fun trySetBluetoothName(name: String?) {
        if (name.isNullOrBlank()) {
            return
        }

        try {
            bluetoothAdapter?.name = name
            val currentName = bluetoothAdapter?.name
            Log.d(TAG, "Set local Bluetooth name request=$name, current=$currentName")
        } catch (e: Exception) {
            Log.e(TAG, "Không set được Bluetooth name", e)
        }
    }

    private fun characteristicKey(characteristic: BluetoothGattCharacteristic): String {
        val serviceUuid = characteristic.service?.uuid?.toString().orEmpty()
        return characteristicKey(serviceUuid, characteristic.uuid.toString())
    }

    private fun characteristicKey(serviceUuid: String, characteristicUuid: String): String {
        return "${serviceUuid.lowercase()}|${characteristicUuid.lowercase()}"
    }

    private fun descriptorKey(descriptor: BluetoothGattDescriptor): String {
        val characteristic = descriptor.characteristic
        val serviceUuid = characteristic?.service?.uuid?.toString().orEmpty()
        val characteristicUuid = characteristic?.uuid?.toString().orEmpty()

        return descriptorKey(
                serviceUuid = serviceUuid,
                characteristicUuid = characteristicUuid,
                descriptorUuid = descriptor.uuid.toString()
        )
    }

    private fun descriptorKey(
            serviceUuid: String,
            characteristicUuid: String,
            descriptorUuid: String
    ): String {
        return "${serviceUuid.lowercase()}|${characteristicUuid.lowercase()}|${descriptorUuid.lowercase()}"
    }

    private fun clientDescriptorKey(
            device: BluetoothDevice,
            descriptor: BluetoothGattDescriptor
    ): String {
        return "${device.address}|${descriptorKey(descriptor)}"
    }

    private fun defaultDescriptorValue(descriptor: BluetoothGattDescriptor): ByteArray {
        return if (descriptor.uuid == UUID_CCCD) {
            byteArrayOf(0x00, 0x00)
        } else {
            byteArrayOf()
        }
    }

    private fun mergeWriteValue(
            oldValue: ByteArray,
            offset: Int,
            writeValue: ByteArray
    ): ByteArray {
        val newSize = maxOf(oldValue.size, offset + writeValue.size)
        val result = ByteArray(newSize)

        oldValue.copyInto(result, endIndex = oldValue.size)
        writeValue.copyInto(result, destinationOffset = offset)

        return result
    }

    private fun describeCccdValue(value: ByteArray): String {
        if (value.size < 2) {
            return "INVALID(${BlePayloadUtils.bytesToHex(value)})"
        }

        return when {
            value[0] == 0x00.toByte() && value[1] == 0x00.toByte() -> "DISABLED"
            value[0] == 0x01.toByte() && value[1] == 0x00.toByte() -> "NOTIFY_ENABLED"
            value[0] == 0x02.toByte() && value[1] == 0x00.toByte() -> "INDICATE_ENABLED"
            else -> "UNKNOWN(${BlePayloadUtils.bytesToHex(value)})"
        }
    }

    companion object {
        private const val TAG = "BleEmulatorManager"

        private val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
