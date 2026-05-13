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
import com.duongtc.blerepeaterlab.model.BleLogEntry
import com.duongtc.blerepeaterlab.model.CapturedBleProfile
import com.duongtc.blerepeaterlab.model.CapturedGattCharacteristic
import com.duongtc.blerepeaterlab.model.EmulatorState
import com.duongtc.blerepeaterlab.model.LogOperation
import com.duongtc.blerepeaterlab.model.LogSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Quản lý việc giả lập BLE Peripheral và GATT Server. */
class BleEmulatorManager(
        private val context: Context,
        private val onLog: (BleLogEntry) -> Unit = {}
) {

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
         * Key: serviceUuid|characteristicUuid
         */
        private val characteristicValues = mutableMapOf<String, ByteArray?>()

        /**
         * Cache giá trị descriptor.
         *
         * Key: serviceUuid|characteristicUuid|descriptorUuid
         *
         * Không dùng riêng descriptorUuid vì CCCD 00002902-... có thể xuất hiện ở rất nhiều
         * characteristic.
         */
        private val descriptorValues = mutableMapOf<String, ByteArray?>()

        /**
         * Cache descriptor theo từng client.
         *
         * Key: deviceAddress|serviceUuid|characteristicUuid|descriptorUuid
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

                                val stateText =
                                        when (newState) {
                                                BluetoothGatt.STATE_CONNECTED -> "STATE_CONNECTED"
                                                BluetoothGatt.STATE_DISCONNECTED -> "STATE_DISCONNECTED"
                                                BluetoothGatt.STATE_CONNECTING -> "STATE_CONNECTING"
                                                BluetoothGatt.STATE_DISCONNECTING -> "STATE_DISCONNECTING"
                                                else -> "UNKNOWN_STATE_$newState"
                                        }

                                emitLog(
                                        source = LogSource.FAKE_GATT_SERVER,
                                        operation =
                                                if (newState == BluetoothGatt.STATE_CONNECTED) {
                                                        LogOperation.CONNECT
                                                } else {
                                                        LogOperation.DISCONNECT
                                                },
                                        statusCode = status,
                                        message =
                                                "[APP->FAKE] GATT client state changed. client=${safeAddress(device)}, status=$status, newState=$stateText"
                                )

                                if (newState == BluetoothGatt.STATE_CONNECTED) {
                                        _emulatorState.value = EmulatorState.CLIENT_CONNECTED
                                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                                        _emulatorState.value = EmulatorState.GATT_SERVER_RUNNING
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

                                val serviceUuid = characteristic.service?.uuid?.toString()
                                val charUuid = characteristic.uuid.toString()
                                val key = characteristicKey(characteristic)
                                val fullValue = characteristicValues[key] ?: byteArrayOf()

                                emitLog(
                                        source = LogSource.FAKE_GATT_SERVER,
                                        operation = LogOperation.READ_CHAR,
                                        serviceUuid = serviceUuid,
                                        characteristicUuid = charUuid,
                                        payload = fullValue,
                                        message =
                                                "[APP->FAKE] Read characteristic request. client=${safeAddress(device)}, requestId=$requestId, offset=$offset, key=$key, cachedLen=${fullValue.size}, utf8=${BlePayloadUtils.tryUtf8Preview(fullValue)}"
                                )

                                if (offset > fullValue.size) {
                                        gattServer?.sendResponse(
                                                device,
                                                requestId,
                                                BluetoothGatt.GATT_INVALID_OFFSET,
                                                offset,
                                                null
                                        )

                                        emitLog(
                                                source = LogSource.FAKE_GATT_SERVER,
                                                operation = LogOperation.READ_CHAR,
                                                serviceUuid = serviceUuid,
                                                characteristicUuid = charUuid,
                                                statusCode = BluetoothGatt.GATT_INVALID_OFFSET,
                                                message =
                                                        "[FAKE->APP] Read characteristic response INVALID_OFFSET. client=${safeAddress(device)}, requestId=$requestId, offset=$offset"
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
                                
                                emitLog(
                                        source = LogSource.FAKE_GATT_SERVER,
                                        operation = LogOperation.READ_CHAR,
                                        serviceUuid = serviceUuid,
                                        characteristicUuid = charUuid,
                                        payload = slicedValue,
                                        statusCode = BluetoothGatt.GATT_SUCCESS,
                                        message =
                                                "[FAKE->APP] Read characteristic response SUCCESS. client=${safeAddress(device)}, requestId=$requestId, offset=$offset, responseLen=${slicedValue.size}, utf8=${BlePayloadUtils.tryUtf8Preview(slicedValue)}"
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

                                val serviceUuid = characteristic.service?.uuid?.toString()
                                val charUuid = characteristic.uuid.toString()
                                val key = characteristicKey(characteristic)

                                emitLog(
                                        source = LogSource.FAKE_GATT_SERVER,
                                        operation = LogOperation.WRITE_CHAR,
                                        serviceUuid = serviceUuid,
                                        characteristicUuid = charUuid,
                                        payload = value,
                                        message =
                                                "[APP->FAKE] Write characteristic request. client=${safeAddress(device)}, requestId=$requestId, preparedWrite=$preparedWrite, responseNeeded=$responseNeeded, offset=$offset, key=$key, len=${value.size}, utf8=${BlePayloadUtils.tryUtf8Preview(value)}"
                                )

                                val oldValue = characteristicValues[key] ?: byteArrayOf()
                                val newValue =
                                        if (offset <= 0) {
                                                value
                                        } else {
                                                mergeWriteValue(oldValue, offset, value)
                                        }

                                characteristicValues[key] = newValue

                                emitLog(
                                        source = LogSource.SYSTEM,
                                        operation = LogOperation.WRITE_CHAR,
                                        serviceUuid = serviceUuid,
                                        characteristicUuid = charUuid,
                                        payload = newValue,
                                        message =
                                                "[FAKE CACHE] Characteristic cache updated. key=$key, oldLen=${oldValue.size}, newLen=${newValue.size}"
                                )

                                /*
                                 * Hiện tại chưa có proxy realtime sang thiết bị thật.
                                 * Log rõ ràng để sau này khi bổ sung bridge có thể so sánh:
                                 * APP->FAKE, FAKE->REAL, REAL->FAKE, FAKE->APP.
                                 */
                                emitLog(
                                        source = LogSource.REAL_GATT_CLIENT,
                                        operation = LogOperation.WRITE_CHAR,
                                        serviceUuid = serviceUuid,
                                        characteristicUuid = charUuid,
                                        payload = value,
                                        message =
                                                "[FAKE->REAL] NOT_IMPLEMENTED. Payload app gửi chưa được forward sang thiết bị thật."
                                )

                                if (responseNeeded) {
                                        gattServer?.sendResponse(
                                                device,
                                                requestId,
                                                BluetoothGatt.GATT_SUCCESS,
                                                offset,
                                                value
                                        )

                                        emitLog(
                                                source = LogSource.FAKE_GATT_SERVER,
                                                operation = LogOperation.WRITE_CHAR,
                                                serviceUuid = serviceUuid,
                                                characteristicUuid = charUuid,
                                                payload = value,
                                                statusCode = BluetoothGatt.GATT_SUCCESS,
                                                message =
                                                        "[FAKE->APP] Write characteristic response SUCCESS. client=${safeAddress(device)}, requestId=$requestId"
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

                                val serviceUuid = descriptor.characteristic?.service?.uuid?.toString()
                                val charUuid = descriptor.characteristic?.uuid?.toString()
                                val descUuid = descriptor.uuid.toString()
                                val baseKey = descriptorKey(descriptor)
                                val clientKey = clientDescriptorKey(device, descriptor)

                                val fullValue =
                                        clientDescriptorValues[clientKey]
                                                ?: descriptorValues[baseKey]
                                                        ?: defaultDescriptorValue(descriptor)

                                emitLog(
                                        source = LogSource.FAKE_GATT_SERVER,
                                        operation = LogOperation.READ_DESC,
                                        serviceUuid = serviceUuid,
                                        characteristicUuid = charUuid,
                                        descriptorUuid = descUuid,
                                        payload = fullValue,
                                        message =
                                                "[APP->FAKE] Read descriptor request. client=${safeAddress(device)}, requestId=$requestId, offset=$offset, key=$baseKey, clientKey=$clientKey, len=${fullValue.size}, utf8=${BlePayloadUtils.tryUtf8Preview(fullValue)}"
                                )

                                if (offset > fullValue.size) {
                                        gattServer?.sendResponse(
                                                device,
                                                requestId,
                                                BluetoothGatt.GATT_INVALID_OFFSET,
                                                offset,
                                                null
                                        )

                                        emitLog(
                                                source = LogSource.FAKE_GATT_SERVER,
                                                operation = LogOperation.READ_DESC,
                                                serviceUuid = serviceUuid,
                                                characteristicUuid = charUuid,
                                                descriptorUuid = descUuid,
                                                statusCode = BluetoothGatt.GATT_INVALID_OFFSET,
                                                message =
                                                        "[FAKE->APP] Read descriptor response INVALID_OFFSET. client=${safeAddress(device)}, requestId=$requestId"
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

                                emitLog(
                                        source = LogSource.FAKE_GATT_SERVER,
                                        operation = LogOperation.READ_DESC,
                                        serviceUuid = serviceUuid,
                                        characteristicUuid = charUuid,
                                        descriptorUuid = descUuid,
                                        payload = slicedValue,
                                        statusCode = BluetoothGatt.GATT_SUCCESS,
                                        message =
                                                "[FAKE->APP] Read descriptor response SUCCESS. client=${safeAddress(device)}, requestId=$requestId, responseLen=${slicedValue.size}, utf8=${BlePayloadUtils.tryUtf8Preview(slicedValue)}"
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

                                val serviceUuid = descriptor.characteristic?.service?.uuid?.toString()
                                val charUuid = descriptor.characteristic?.uuid?.toString()
                                val descUuid = descriptor.uuid.toString()
                                val baseKey = descriptorKey(descriptor)
                                val clientKey = clientDescriptorKey(device, descriptor)

                                emitLog(
                                        source = LogSource.FAKE_GATT_SERVER,
                                        operation = LogOperation.WRITE_DESC,
                                        serviceUuid = serviceUuid,
                                        characteristicUuid = charUuid,
                                        descriptorUuid = descUuid,
                                        payload = value,
                                        message =
                                                "[APP->FAKE] Write descriptor request. client=${safeAddress(device)}, requestId=$requestId, preparedWrite=$preparedWrite, responseNeeded=$responseNeeded, offset=$offset, key=$baseKey, clientKey=$clientKey, len=${value.size}, utf8=${BlePayloadUtils.tryUtf8Preview(value)}"
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

                                if (descriptor.uuid == UUID_CCCD) {
                                        emitLog(
                                                source = LogSource.FAKE_GATT_SERVER,
                                                operation = LogOperation.WRITE_DESC,
                                                serviceUuid = serviceUuid,
                                                characteristicUuid = charUuid,
                                                descriptorUuid = descUuid,
                                                payload = newValue,
                                                message =
                                                        "[APP->FAKE] CCCD updated. client=${safeAddress(device)}, state=${describeCccdValue(newValue)}"
                                        )
                                } else {
                                        emitLog(
                                                source = LogSource.SYSTEM,
                                                operation = LogOperation.WRITE_DESC,
                                                serviceUuid = serviceUuid,
                                                characteristicUuid = charUuid,
                                                descriptorUuid = descUuid,
                                                payload = newValue,
                                                message =
                                                        "[FAKE CACHE] Descriptor client cache updated. client=${safeAddress(device)}, oldLen=${oldValue.size}, newLen=${newValue.size}"
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

                                        emitLog(
                                                source = LogSource.FAKE_GATT_SERVER,
                                                operation = LogOperation.WRITE_DESC,
                                                serviceUuid = serviceUuid,
                                                characteristicUuid = charUuid,
                                                descriptorUuid = descUuid,
                                                payload = value,
                                                statusCode = BluetoothGatt.GATT_SUCCESS,
                                                message =
                                                        "[FAKE->APP] Write descriptor response SUCCESS. client=${safeAddress(device)}, requestId=$requestId"
                                        )
                                }
                        }
                }

        private val advertiseCallback =
                object : AdvertiseCallback() {

                        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                                super.onStartSuccess(settingsInEffect)

                                _emulatorState.value = EmulatorState.ADVERTISING

                                emitLog(
                                        source = LogSource.ADVERTISER,
                                        operation = LogOperation.START_ADVERTISE,
                                        message =
                                                "[FAKE ADVERTISER] Start advertising SUCCESS. mode=${settingsInEffect.mode}, txPower=${settingsInEffect.txPowerLevel}, connectable=${settingsInEffect.isConnectable}, timeout=${settingsInEffect.timeout}"
                                )
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

                                _emulatorState.value = EmulatorState.ERROR

                                emitLog(
                                        source = LogSource.ADVERTISER,
                                        operation = LogOperation.ERROR,
                                        statusCode = errorCode,
                                        message =
                                                "[FAKE ADVERTISER] Start advertising FAILED. code=$errorCode, reason=$reason"
                                )
                        }
                }

        @SuppressLint("MissingPermission")
        fun start(profile: CapturedBleProfile) {
                stop()

                emitLog(
                        source = LogSource.SYSTEM,
                        operation = LogOperation.START_ADVERTISE,
                        message =
                                "[EMULATOR] Start requested. profileName=${profile.deviceName}, address=${profile.deviceAddress}, services=${profile.services.size}, advertiseServiceUuids=${profile.advertiseServiceUuids}, manufacturerDataCount=${profile.manufacturerData.size}"
                )

                if (bluetoothAdapter == null) {
                        _emulatorState.value = EmulatorState.ERROR
                        emitLog(
                                LogSource.SYSTEM,
                                LogOperation.ERROR,
                                message = "[EMULATOR] Bluetooth adapter null"
                        )
                        return
                }

                if (!bluetoothAdapter.isEnabled) {
                        _emulatorState.value = EmulatorState.ERROR
                        emitLog(LogSource.SYSTEM, LogOperation.ERROR, message = "[EMULATOR] Bluetooth chưa bật")
                        return
                }

                if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
                        _emulatorState.value = EmulatorState.ERROR
                        emitLog(
                                LogSource.SYSTEM,
                                LogOperation.ERROR,
                                message = "[EMULATOR] Android device không hỗ trợ multiple BLE advertising"
                        )
                        return
                }

                advertiser = bluetoothAdapter.bluetoothLeAdvertiser
                if (advertiser == null) {
                        _emulatorState.value = EmulatorState.ERROR
                        emitLog(
                                LogSource.ADVERTISER,
                                LogOperation.ERROR,
                                message = "[FAKE ADVERTISER] BluetoothLeAdvertiser null"
                        )
                        return
                }

                gattServer = bluetoothManager.openGattServer(context, serverCallback)
                if (gattServer == null) {
                        _emulatorState.value = EmulatorState.ERROR
                        emitLog(
                                LogSource.FAKE_GATT_SERVER,
                                LogOperation.ERROR,
                                message = "[FAKE GATT] Không mở được BluetoothGattServer"
                        )
                        return
                }

                characteristicValues.clear()
                descriptorValues.clear()
                clientDescriptorValues.clear()

                createGattDatabase(profile)

                val mainServiceUuid =
                        profile.advertiseServiceUuids.firstOrNull() ?: profile.services.firstOrNull()?.uuid

                if (mainServiceUuid.isNullOrBlank()) {
                        _emulatorState.value = EmulatorState.ERROR
                        emitLog(
                                source = LogSource.ADVERTISER,
                                operation = LogOperation.ERROR,
                                message = "[FAKE ADVERTISER] Không có service UUID nào để advertise"
                        )
                        return
                }

                val emulatorDeviceName = buildEmulatorDeviceName(profile.deviceName)
                trySetBluetoothName(emulatorDeviceName)

                val settings =
                        AdvertiseSettings.Builder()
                                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                                .setConnectable(true)
                                .setTimeout(0)
                                .build()

                /*
                 * Advertising chính: ưu tiên Service UUID để app chính chủ scan filter thấy.
                 */
                val advertiseDataBuilder =
                        AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)

                advertiseDataBuilder.addServiceUuid(ParcelUuid(UUID.fromString(mainServiceUuid)))

                /*
                 * Scan response: chứa device name + manufacturer data nếu đủ dung lượng.
                 */
                val scanResponseBuilder =
                        AdvertiseData.Builder()
                                .setIncludeDeviceName(!emulatorDeviceName.isNullOrBlank())
                                .setIncludeTxPowerLevel(false)

                profile.manufacturerData.forEach { (id, bytes) ->
                        scanResponseBuilder.addManufacturerData(id, bytes)
                        emitLog(
                                source = LogSource.ADVERTISER,
                                operation = LogOperation.START_ADVERTISE,
                                payload = bytes,
                                message =
                                        "[FAKE ADVERTISER] Add manufacturerData. id=$id, len=${bytes.size}, utf8=${BlePayloadUtils.tryUtf8Preview(bytes)}"
                        )
                }

                val advertiseData: AdvertiseData
                val scanResponse: AdvertiseData

                try {
                        advertiseData = advertiseDataBuilder.build()
                        scanResponse = scanResponseBuilder.build()
                } catch (e: IllegalArgumentException) {
                        _emulatorState.value = EmulatorState.ERROR
                        emitLog(
                                source = LogSource.ADVERTISER,
                                operation = LogOperation.ERROR,
                                message =
                                        "[FAKE ADVERTISER] Build AdvertiseData lỗi. Có thể advertising/scanResponse quá lớn. error=${e.message}"
                        )
                        return
                }

                _emulatorState.value = EmulatorState.STARTING_ADVERTISER

                emitLog(
                        source = LogSource.ADVERTISER,
                        operation = LogOperation.START_ADVERTISE,
                        message =
                                "[FAKE ADVERTISER] Calling startAdvertising. mainServiceUuid=$mainServiceUuid, includeName=${!emulatorDeviceName.isNullOrBlank()}, emulatorName=$emulatorDeviceName, manufacturerDataCount=${profile.manufacturerData.size}"
                )

                try {
                        advertiser?.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
                } catch (e: SecurityException) {
                        _emulatorState.value = EmulatorState.ERROR
                        emitLog(
                                source = LogSource.ADVERTISER,
                                operation = LogOperation.ERROR,
                                message =
                                        "[FAKE ADVERTISER] Start advertising lỗi do thiếu quyền. error=${e.message}"
                        )
                } catch (e: Exception) {
                        _emulatorState.value = EmulatorState.ERROR
                        emitLog(
                                source = LogSource.ADVERTISER,
                                operation = LogOperation.ERROR,
                                message = "[FAKE ADVERTISER] Start advertising lỗi. error=${e.message}"
                        )
                }
        }

        @SuppressLint("MissingPermission")
        fun stop() {
                emitLog(
                        source = LogSource.SYSTEM,
                        operation = LogOperation.STOP_ADVERTISE,
                        message = "[EMULATOR] Stop requested"
                )

                try {
                        advertiser?.stopAdvertising(advertiseCallback)
                        emitLog(
                                source = LogSource.ADVERTISER,
                                operation = LogOperation.STOP_ADVERTISE,
                                message = "[FAKE ADVERTISER] stopAdvertising called"
                        )
                } catch (e: Exception) {
                        emitLog(
                                source = LogSource.ADVERTISER,
                                operation = LogOperation.ERROR,
                                message = "[FAKE ADVERTISER] Stop advertising lỗi. error=${e.message}"
                        )
                }

                advertiser = null

                try {
                        gattServer?.close()
                        emitLog(
                                source = LogSource.FAKE_GATT_SERVER,
                                operation = LogOperation.DISCONNECT,
                                message = "[FAKE GATT] GATT server closed"
                        )
                } catch (e: Exception) {
                        emitLog(
                                source = LogSource.FAKE_GATT_SERVER,
                                operation = LogOperation.ERROR,
                                message = "[FAKE GATT] Close GATT server lỗi. error=${e.message}"
                        )
                }

                gattServer = null

                characteristicValues.clear()
                descriptorValues.clear()
                clientDescriptorValues.clear()

                _emulatorState.value = EmulatorState.IDLE
        }

        @SuppressLint("MissingPermission")
        private fun createGattDatabase(profile: CapturedBleProfile) {
                profile.services.forEachIndexed { serviceIndex, capturedService ->
                        val serviceUuid = UUID.fromString(capturedService.uuid)

                        val service =
                                BluetoothGattService(
                                        serviceUuid,
                                        capturedService.type ?: BluetoothGattService.SERVICE_TYPE_PRIMARY
                                )

                        emitLog(
                                source = LogSource.FAKE_GATT_SERVER,
                                operation = LogOperation.DISCOVER_SERVICE,
                                serviceUuid = capturedService.uuid,
                                message =
                                        "[FAKE GATT] Create service[$serviceIndex]. uuid=${capturedService.uuid}, type=${capturedService.type}, characteristics=${capturedService.characteristics.size}"
                        )

                        capturedService.characteristics.forEachIndexed { charIndex, capturedChar ->
                                val characteristicUuid = UUID.fromString(capturedChar.uuid)

                                val characteristic =
                                        BluetoothGattCharacteristic(
                                                characteristicUuid,
                                                mapProperties(capturedChar),
                                                mapPermissions(capturedChar)
                                        )

                                val charKey = characteristicKey(capturedService.uuid, capturedChar.uuid)
                                characteristicValues[charKey] = capturedChar.value

                                emitLog(
                                        source = LogSource.FAKE_GATT_SERVER,
                                        operation = LogOperation.DISCOVER_SERVICE,
                                        serviceUuid = capturedService.uuid,
                                        characteristicUuid = capturedChar.uuid,
                                        payload = capturedChar.value,
                                        message =
                                                "[FAKE GATT] Create characteristic[$serviceIndex.$charIndex]. key=$charKey, properties=${capturedChar.properties}, mappedProperties=${mapProperties(capturedChar)}, mappedPermissions=${mapPermissions(capturedChar)}, canRead=${capturedChar.canRead}, canWrite=${capturedChar.canWrite}, canWriteNoResponse=${capturedChar.canWriteNoResponse}, canNotify=${capturedChar.canNotify}, canIndicate=${capturedChar.canIndicate}, cachedLen=${capturedChar.value?.size ?: 0}, utf8=${BlePayloadUtils.tryUtf8Preview(capturedChar.value)}"
                                )

                                capturedChar.descriptors.forEachIndexed { descIndex, capturedDesc ->
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

                                        emitLog(
                                                source = LogSource.FAKE_GATT_SERVER,
                                                operation = LogOperation.DISCOVER_SERVICE,
                                                serviceUuid = capturedService.uuid,
                                                characteristicUuid = capturedChar.uuid,
                                                descriptorUuid = capturedDesc.uuid,
                                                payload = capturedDesc.value,
                                                message =
                                                        "[FAKE GATT] Create descriptor[$serviceIndex.$charIndex.$descIndex]. key=$descKey, cachedLen=${capturedDesc.value?.size ?: 0}, utf8=${BlePayloadUtils.tryUtf8Preview(capturedDesc.value)}"
                                        )
                                }

                                ensureCccdIfNeeded(
                                        serviceUuid = capturedService.uuid,
                                        capturedChar = capturedChar,
                                        characteristic = characteristic
                                )

                                val added = service.addCharacteristic(characteristic)

                                emitLog(
                                        source = LogSource.FAKE_GATT_SERVER,
                                        operation = LogOperation.DISCOVER_SERVICE,
                                        serviceUuid = capturedService.uuid,
                                        characteristicUuid = capturedChar.uuid,
                                        message =
                                                "[FAKE GATT] Add characteristic to service. service=${capturedService.uuid}, char=${capturedChar.uuid}, added=$added"
                                )
                        }

                        val ok = gattServer?.addService(service)

                        emitLog(
                                source = LogSource.FAKE_GATT_SERVER,
                                operation = LogOperation.DISCOVER_SERVICE,
                                serviceUuid = capturedService.uuid,
                                statusCode = if (ok == true) BluetoothGatt.GATT_SUCCESS else null,
                                message =
                                        "[FAKE GATT] Add service to GATT server. uuid=${capturedService.uuid}, ok=$ok"
                        )
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

                emitLog(
                        source = LogSource.FAKE_GATT_SERVER,
                        operation = LogOperation.DISCOVER_SERVICE,
                        serviceUuid = serviceUuid,
                        characteristicUuid = capturedChar.uuid,
                        descriptorUuid = UUID_CCCD.toString(),
                        payload = byteArrayOf(0x00, 0x00),
                        message =
                                "[FAKE GATT] Auto add CCCD because characteristic supports notify/indicate"
                )
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
                        emitLog(
                                source = LogSource.ADVERTISER,
                                operation = LogOperation.START_ADVERTISE,
                                message =
                                        "[FAKE ADVERTISER] Set local Bluetooth name requested. requested=$name, current=${bluetoothAdapter?.name}"
                        )
                } catch (e: Exception) {
                        emitLog(
                                source = LogSource.ADVERTISER,
                                operation = LogOperation.ERROR,
                                message =
                                        "[FAKE ADVERTISER] Không set được Bluetooth name. requested=$name, error=${e.message}"
                        )
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
                return "${safeAddress(device)}|${descriptorKey(descriptor)}"
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

        private fun emitLog(
                source: LogSource,
                operation: LogOperation,
                serviceUuid: String? = null,
                characteristicUuid: String? = null,
                descriptorUuid: String? = null,
                payload: ByteArray? = null,
                statusCode: Int? = null,
                message: String
        ) {
                val payloadHex = payload?.let { BlePayloadUtils.bytesToHex(it) }
                val entry =
                        BleLogEntry(
                                source = source,
                                operation = operation,
                                serviceUuid = serviceUuid,
                                characteristicUuid = characteristicUuid,
                                descriptorUuid = descriptorUuid,
                                payloadHex = payloadHex,
                                statusCode = statusCode,
                                message = message
                        )

                val time = LOG_TIME_FORMAT.format(Date(entry.timestamp))

                val logMessage = buildString {
                        append("[$time] ")
                        append("[${entry.source}] ")
                        append("[${entry.operation}] ")
                        append(message)

                        if (!serviceUuid.isNullOrBlank()) {
                                append(" | service=$serviceUuid")
                        }

                        if (!characteristicUuid.isNullOrBlank()) {
                                append(" | char=$characteristicUuid")
                        }

                        if (!descriptorUuid.isNullOrBlank()) {
                                append(" | desc=$descriptorUuid")
                        }

                        if (statusCode != null) {
                                append(" | status=$statusCode")
                        }

                        if (!payloadHex.isNullOrBlank()) {
                                append(" | payloadHex=$payloadHex")
                        }
                }

                Log.d(TAG, logMessage)
                onLog(entry)
        }

        private fun safeAddress(device: BluetoothDevice): String {
                return try {
                        device.address ?: "unknown"
                } catch (e: Exception) {
                        "unknown"
                }
        }

        private fun buildEmulatorDeviceName(originalName: String?): String {
                val baseName =
                        if (originalName.isNullOrBlank()) {
                                "BLE Device"
                        } else {
                                originalName.trim()
                        }

                return if (baseName.endsWith(" rep", ignoreCase = true)) {
                        baseName
                } else {
                        "$baseName rep"
                }
        }

        companion object {
                private const val TAG = "BleEmulatorManager"

                private val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

                private val LOG_TIME_FORMAT =
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        }
}
