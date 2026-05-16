package com.duongtc.blerepeaterlab.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.duongtc.blerepeaterlab.model.BleScanItem
import com.duongtc.blerepeaterlab.model.CapturedBleProfile
import com.duongtc.blerepeaterlab.model.CapturedGattCharacteristic
import com.duongtc.blerepeaterlab.model.CapturedGattDescriptor
import com.duongtc.blerepeaterlab.model.CapturedGattService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Quản lý việc kết nối tới thiết bị thật và capture GATT profile. */
class RealGattCaptureManager(private val context: Context) : RealGattBridge {

    private var selectedScanItem: BleScanItem? = null
    private val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null

    private val _captureState = MutableStateFlow(CaptureState.IDLE)
    val captureState = _captureState.asStateFlow()

    private val _capturedProfile = MutableStateFlow<CapturedBleProfile?>(null)
    val capturedProfile = _capturedProfile.asStateFlow()

    // Mutex để đảm bảo các thao tác GATT chạy tuần tự
    private val mutex = Mutex()
    private var readDeferred: CompletableDeferred<ByteArray?>? = null
    private var writeDeferred: CompletableDeferred<Boolean>? = null
    private var descriptorDeferred: CompletableDeferred<Boolean>? = null
    private var operationDeferred: CompletableDeferred<Any?>? = null

    private var onRealtimeCharacteristicChanged: ((serviceUuid: String, characteristicUuid: String, value: ByteArray) -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    private val gattCallback =
            object : BluetoothGattCallback() {
                @SuppressLint("MissingPermission")
                override fun onConnectionStateChange(
                        gatt: BluetoothGatt,
                        status: Int,
                        newState: Int
                ) {
                    super.onConnectionStateChange(gatt, status, newState)
                    Log.d(
                            "RealGattCaptureManager",
                            "onConnectionStateChange: status=$status, newState=$newState"
                    )

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e("RealGattCaptureManager", "Lỗi kết nối: status=$status")
                        _captureState.value = CaptureState.ERROR
                        operationDeferred?.complete(false)
                        return
                    }

                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        _captureState.value = CaptureState.CONNECTED
                        Log.d("RealGattCaptureManager", "Đã kết nối, request MTU 247 rồi discover services...")
                        gatt.requestMtu(247)
                        _captureState.value = CaptureState.DISCOVERING_SERVICES
                        gatt.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        _captureState.value = CaptureState.DISCONNECTED
                        Log.d("RealGattCaptureManager", "Đã ngắt kết nối")
                        operationDeferred?.complete(false)
                    }
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    super.onServicesDiscovered(gatt, status)
                    Log.d("RealGattCaptureManager", "onServicesDiscovered: status=$status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        _captureState.value = CaptureState.SERVICES_DISCOVERED
                        // Bắt đầu quá trình đọc dữ liệu tuần tự
                        scope.launch { captureFullProfile(gatt) }
                    } else {
                        _captureState.value = CaptureState.ERROR
                        Log.e("RealGattCaptureManager", "Discover services thất bại: $status")
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int
                ) {
                    super.onCharacteristicRead(gatt, characteristic, status)
                    Log.d(
                            "RealGattCaptureManager",
                            "onCharacteristicRead: ${characteristic.uuid}, status=$status"
                    )
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        operationDeferred?.complete(characteristic.value)
                        readDeferred?.complete(characteristic.value)
                    } else {
                        operationDeferred?.complete(null)
                        readDeferred?.complete(null)
                    }
                }

                @Suppress("DEPRECATION")
                override fun onDescriptorRead(
                        gatt: BluetoothGatt,
                        descriptor: BluetoothGattDescriptor,
                        status: Int
                ) {
                    super.onDescriptorRead(gatt, descriptor, status)
                    Log.d(
                            "RealGattCaptureManager",
                            "onDescriptorRead: ${descriptor.uuid}, status=$status"
                    )
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        operationDeferred?.complete(descriptor.value)
                        descriptorDeferred?.complete(true)
                    } else {
                        operationDeferred?.complete(null)
                        descriptorDeferred?.complete(false)
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicWrite(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int
                ) {
                    super.onCharacteristicWrite(gatt, characteristic, status)
                    Log.d("RealGattCaptureManager", "onCharacteristicWrite: ${characteristic.uuid}, status=$status")
                    writeDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
                }

                @Suppress("DEPRECATION")
                override fun onDescriptorWrite(
                        gatt: BluetoothGatt,
                        descriptor: BluetoothGattDescriptor,
                        status: Int
                ) {
                    super.onDescriptorWrite(gatt, descriptor, status)
                    val serviceUuid = descriptor.characteristic?.service?.uuid?.toString() ?: ""
                    val charUuid = descriptor.characteristic?.uuid?.toString() ?: ""
                    Log.d("RealGattCaptureManager", "[REAL_GATT_CLIENT] [WRITE_DESC] [REAL] Descriptor write completed. status=$status | service=$serviceUuid | char=$charUuid | desc=${descriptor.uuid}")
                    descriptorDeferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
                }

                override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray
                ) {
                    super.onCharacteristicChanged(gatt, characteristic, value)
                    val serviceUuid = characteristic.service?.uuid?.toString() ?: return
                    val charUuid = characteristic.uuid.toString()
                    handleCharacteristicChanged(serviceUuid, charUuid, value)
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic
                ) {
                    super.onCharacteristicChanged(gatt, characteristic)
                    val serviceUuid = characteristic.service?.uuid?.toString() ?: return
                    val charUuid = characteristic.uuid.toString()
                    val value = characteristic.value ?: byteArrayOf()
                    handleCharacteristicChanged(serviceUuid, charUuid, value)
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    super.onMtuChanged(gatt, mtu, status)
                    Log.d("RealGattCaptureManager", "onMtuChanged: mtu=$mtu, status=$status")
                }
            }

    private fun handleCharacteristicChanged(
            serviceUuid: String,
            charUuid: String,
            value: ByteArray
    ) {
        Log.d("RealGattCaptureManager", "onCharacteristicChanged: $charUuid, len=${value.size}")
        onRealtimeCharacteristicChanged?.invoke(serviceUuid, charUuid, value)
        // Nếu có lưu cache ở manager này thì cập nhật
        _capturedProfile.value?.let { profile ->
            profile.services.find { it.uuid.equals(serviceUuid, ignoreCase = true) }
                    ?.characteristics?.find { it.uuid.equals(charUuid, ignoreCase = true) }
                    ?.value = value
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(scanItem: BleScanItem) {
        if (bluetoothAdapter == null) return

        selectedScanItem = scanItem

        val device = bluetoothAdapter.getRemoteDevice(scanItem.address)
        _captureState.value = CaptureState.CONNECTING
        bluetoothGatt =
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun setOnRealtimeCharacteristicChanged(listener: ((serviceUuid: String, characteristicUuid: String, value: ByteArray) -> Unit)?) {
        onRealtimeCharacteristicChanged = listener
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        _captureState.value = CaptureState.DISCONNECTED
    }

    @SuppressLint("MissingPermission")
    fun close() {
        bluetoothGatt?.close()
        bluetoothGatt = null
        _captureState.value = CaptureState.IDLE
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureFullProfile(gatt: BluetoothGatt) {
        _captureState.value = CaptureState.READING_DATA
        val device = gatt.device
        val services = gatt.services

        val capturedServices = mutableListOf<CapturedGattService>()

        for (service in services) {
            val capturedChars = mutableListOf<CapturedGattCharacteristic>()
            for (char in service.characteristics) {
                var value: ByteArray? = null
                if (isReadable(char)) {
                    value = readCharacteristicSuspending(gatt, char)
                }

                val capturedDescs = mutableListOf<CapturedGattDescriptor>()
                for (desc in char.descriptors) {
                    var descValue: ByteArray? = null
                    if (isReadable(desc)) {
                        descValue = readDescriptorSuspending(gatt, desc)
                    }
                    capturedDescs.add(CapturedGattDescriptor(desc.uuid.toString(), descValue))
                }

                capturedChars.add(
                        CapturedGattCharacteristic(
                                uuid = char.uuid.toString(),
                                properties = char.properties,
                                permissionsForServer =
                                        char.permissions, // Tạm thời lấy permission này
                                value = value,
                                descriptors = capturedDescs,
                                canRead = isReadable(char),
                                canWrite = isWritable(char),
                                canWriteNoResponse = isWritableNoResponse(char),
                                canNotify = isNotifiable(char),
                                canIndicate = isIndicatable(char)
                        )
                )
            }
            capturedServices.add(
                    CapturedGattService(
                            uuid = service.uuid.toString(),
                            type = service.type,
                            characteristics = capturedChars
                    )
            )
        }

        val scanItem = selectedScanItem

        val profile =
                CapturedBleProfile(
                        deviceName = scanItem?.name ?: device.name,
                        deviceAddress = scanItem?.address ?: device.address,
                        lastRssi = scanItem?.rssi ?: 0,
                        advertiseServiceUuids = scanItem?.serviceUuids ?: emptyList(),
                        manufacturerData = scanItem?.manufacturerData ?: emptyMap(),
                        serviceData = scanItem?.serviceData ?: emptyMap(),
                        services = capturedServices
                )

        _capturedProfile.value = profile
        _captureState.value = CaptureState.COMPLETED
        Log.d("RealGattCaptureManager", "Capture hoàn thành!")
        Log.d("RealGattCaptureManager", "Captured profile name=${profile.deviceName}")
        Log.d("RealGattCaptureManager", "Captured profile address=${profile.deviceAddress}")
        Log.d("RealGattCaptureManager", "Captured profile rssi=${profile.lastRssi}")
        Log.d(
                "RealGattCaptureManager",
                "Captured advertiseServiceUuids=${profile.advertiseServiceUuids}"
        )
        Log.d(
                "RealGattCaptureManager",
                "Captured manufacturerData count=${profile.manufacturerData.size}"
        )

        profile.manufacturerData.forEach { (id, bytes) ->
            Log.d(
                    "RealGattCaptureManager",
                    "Captured manufacturerData id=$id, hex=${BlePayloadUtils.bytesToHex(bytes)}"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readCharacteristicSuspending(
            gatt: BluetoothGatt,
            char: BluetoothGattCharacteristic
    ): ByteArray? =
            mutex.withLock {
                operationDeferred = CompletableDeferred()
                gatt.readCharacteristic(char)
                // Timeout 2 giây cho mỗi thao tác
                val result = withTimeoutOrNull(2000) { operationDeferred?.await() }
                return result as? ByteArray
            }

    @SuppressLint("MissingPermission")
    override suspend fun readCharacteristicRealtime(serviceUuid: String, characteristicUuid: String): ByteArray? {
        val gatt = bluetoothGatt ?: run {
            Log.e("RealGattCaptureManager", "readCharacteristicRealtime: GATT is null")
            return null
        }
        val service = gatt.getService(java.util.UUID.fromString(serviceUuid)) ?: run {
            Log.e("RealGattCaptureManager", "readCharacteristicRealtime: Service not found $serviceUuid")
            return null
        }
        val char = service.getCharacteristic(java.util.UUID.fromString(characteristicUuid)) ?: run {
            Log.e("RealGattCaptureManager", "readCharacteristicRealtime: Characteristic not found $characteristicUuid")
            return null
        }

        return mutex.withLock {
            readDeferred = CompletableDeferred()
            val success = gatt.readCharacteristic(char)
            if (!success) return@withLock null
            val result = withTimeoutOrNull(3000) { readDeferred?.await() }
            if (result == null || result.isEmpty()) {
                Log.w("RealGattCaptureManager", "readCharacteristicRealtime: $characteristicUuid returned empty/null")
            }
            result
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun writeCharacteristicRealtime(
            serviceUuid: String,
            characteristicUuid: String,
            value: ByteArray,
            writeType: Int
    ): Boolean {
        val gatt = bluetoothGatt ?: run {
            Log.e("RealGattCaptureManager", "writeCharacteristicRealtime: GATT is null")
            return false
        }
        val service = gatt.getService(java.util.UUID.fromString(serviceUuid)) ?: run {
            Log.e("RealGattCaptureManager", "writeCharacteristicRealtime: Service not found $serviceUuid")
            return false
        }
        val char = service.getCharacteristic(java.util.UUID.fromString(characteristicUuid)) ?: run {
            Log.e("RealGattCaptureManager", "writeCharacteristicRealtime: Characteristic not found $characteristicUuid")
            return false
        }

        return mutex.withLock {
            writeDeferred = CompletableDeferred()
            char.writeType = writeType
            
            val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, value, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                char.value = value
                gatt.writeCharacteristic(char)
            }
            
            if (!success) return@withLock false
            withTimeoutOrNull(3000) { writeDeferred?.await() } ?: false
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun setCharacteristicNotificationRealtime(
            serviceUuid: String,
            characteristicUuid: String,
            enable: Boolean,
            indication: Boolean
    ): Boolean {
        val gatt = bluetoothGatt ?: return false
        val service = gatt.getService(java.util.UUID.fromString(serviceUuid)) ?: return false
        val char = service.getCharacteristic(java.util.UUID.fromString(characteristicUuid)) ?: return false

        return mutex.withLock {
            gatt.setCharacteristicNotification(char, enable)
            val cccdUuid = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            val descriptor = char.getDescriptor(cccdUuid) ?: return@withLock false

            descriptorDeferred = CompletableDeferred()
            val value = if (enable) {
                if (indication) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            
            val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
            
            if (!success) return@withLock false
            withTimeoutOrNull(3000) { descriptorDeferred?.await() } ?: false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readDescriptorSuspending(
            gatt: BluetoothGatt,
            desc: BluetoothGattDescriptor
    ): ByteArray? =
            mutex.withLock {
                operationDeferred = CompletableDeferred()
                gatt.readDescriptor(desc)
                // Timeout 2 giây cho mỗi thao tác
                val result = withTimeoutOrNull(2000) { operationDeferred?.await() }
                return result as? ByteArray
            }

    private fun isReadable(char: BluetoothGattCharacteristic): Boolean {
        return (char.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
    }

    private fun isWritable(char: BluetoothGattCharacteristic): Boolean {
        return (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
    }

    private fun isWritableNoResponse(char: BluetoothGattCharacteristic): Boolean {
        return (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
    }

    private fun isNotifiable(char: BluetoothGattCharacteristic): Boolean {
        return (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
    }

    private fun isIndicatable(char: BluetoothGattCharacteristic): Boolean {
        return (char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    }

    private fun isReadable(desc: BluetoothGattDescriptor): Boolean {
        // Hầu hết descriptor đều đọc được, hoặc có thể thử đọc
        return true
    }
}

interface RealGattBridge {
    suspend fun readCharacteristicRealtime(serviceUuid: String, characteristicUuid: String): ByteArray?
    suspend fun writeCharacteristicRealtime(serviceUuid: String, characteristicUuid: String, value: ByteArray, writeType: Int): Boolean
    suspend fun setCharacteristicNotificationRealtime(serviceUuid: String, characteristicUuid: String, enable: Boolean, indication: Boolean): Boolean
    fun setOnRealtimeCharacteristicChanged(listener: ((serviceUuid: String, characteristicUuid: String, value: ByteArray) -> Unit)?)
}

enum class CaptureState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCOVERING_SERVICES,
    SERVICES_DISCOVERED,
    READING_DATA,
    COMPLETED,
    DISCONNECTED,
    ERROR
}
