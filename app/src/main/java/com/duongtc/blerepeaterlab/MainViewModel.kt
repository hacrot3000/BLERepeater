package com.duongtc.blerepeaterlab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.duongtc.blerepeaterlab.ble.BleEmulatorManager
import com.duongtc.blerepeaterlab.ble.BlePayloadUtils
import com.duongtc.blerepeaterlab.ble.BleScannerManager
import com.duongtc.blerepeaterlab.ble.RealGattCaptureManager
import com.duongtc.blerepeaterlab.model.BleLogEntry
import com.duongtc.blerepeaterlab.model.BleScanItem
import com.duongtc.blerepeaterlab.model.CapturedBleProfile
import com.duongtc.blerepeaterlab.model.LogOperation
import com.duongtc.blerepeaterlab.model.LogSource
import com.duongtc.blerepeaterlab.util.ExportUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** ViewModel chính quản lý trạng thái của ứng dụng. */
class MainViewModel(application: Application) : AndroidViewModel(application) {

        private val scannerManager = BleScannerManager(application)
        private val captureManager = RealGattCaptureManager(application)
        
        private val emulatorManager = BleEmulatorManager(application, captureManager) { entry -> addLog(entry) }

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
                                        message =
                                                "Bắt đầu giả lập profile=${profile.deviceName ?: "Không rõ tên"}"
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
                currentLogs.add(0, entry)
                _logs.value = currentLogs
        }

        fun clearLogs() {
                _logs.value = emptyList()
        }

        fun exportSystemLogs() {
                val content = buildSystemLogsText(_logs.value)

                ExportUtils.shareTextFile(
                        context = getApplication(),
                        fileName = "ble_system_logs_${fileTime()}.txt",
                        chooserTitle = "Chia sẻ logs hệ thống",
                        content = content
                )
        }

        fun exportCapturedProfile() {
                val profile = capturedProfile.value
                if (profile == null) {
                        addLog(
                                BleLogEntry(
                                        source = LogSource.SYSTEM,
                                        operation = LogOperation.ERROR,
                                        message = "Không có profile để export"
                                )
                        )
                        return
                }

                val content = buildCapturedProfileText(profile)

                ExportUtils.shareTextFile(
                        context = getApplication(),
                        fileName = "ble_captured_profile_${fileTime()}.txt",
                        chooserTitle = "Chia sẻ chi tiết thiết bị",
                        content = content
                )
        }

        private fun buildSystemLogsText(logs: List<BleLogEntry>): String {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                return buildString {
                        appendLine("BLE Repeater Lab - System Logs")
                        appendLine("Exported at: ${dateFormat.format(Date())}")
                        appendLine("Total logs: ${logs.size}")
                        appendLine("=".repeat(120))
                        appendLine()

                        logs.reversed().forEachIndexed { index, log ->
                                val payloadHex = log.payloadHex.orEmpty()
                                val normalizedPayloadHex =
                                        if (payloadHex.isBlank()) {
                                                ""
                                        } else {
                                                payloadHex
                                                        .replace(" ", "")
                                                        .replace("0x", "", ignoreCase = true)
                                                        .uppercase(Locale.US)
                                        }

                                val payloadLengthBytes =
                                        if (normalizedPayloadHex.isBlank()) {
                                                0
                                        } else {
                                                normalizedPayloadHex.length / 2
                                        }

                                val payloadUtf8Preview =
                                        hexToByteArrayOrNull(normalizedPayloadHex)
                                                ?.let { BlePayloadUtils.tryUtf8Preview(it) }
                                                .orEmpty()

                                appendLine("#${index + 1}")
                                appendLine("time=${dateFormat.format(Date(log.timestamp))}")
                                appendLine("timestampMs=${log.timestamp}")
                                appendLine("source=${log.source}")
                                appendLine("operation=${log.operation}")
                                appendLine("serviceUuid=${log.serviceUuid ?: ""}")
                                appendLine("characteristicUuid=${log.characteristicUuid ?: ""}")
                                appendLine("descriptorUuid=${log.descriptorUuid ?: ""}")
                                appendLine("statusCode=${log.statusCode ?: ""}")

                                appendLine("[PAYLOAD]")
                                appendLine("payloadLengthBytes=$payloadLengthBytes")
                                appendLine(
                                        "payloadHex=${if (normalizedPayloadHex.isBlank()) "" else "0x$normalizedPayloadHex"}"
                                )
                                appendLine("payloadUtf8Preview=$payloadUtf8Preview")

                                appendLine("[MESSAGE]")
                                appendLine(log.message)

                                appendLine("-".repeat(120))
                        }
                }
        }

        private fun hexToByteArrayOrNull(hex: String): ByteArray? {
                val cleanHex = hex.replace(" ", "").replace("0x", "", ignoreCase = true)

                if (cleanHex.isBlank()) {
                        return byteArrayOf()
                }

                if (cleanHex.length % 2 != 0) {
                        return null
                }

                return try {
                        ByteArray(cleanHex.length / 2) { index ->
                                cleanHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                        }
                } catch (e: Exception) {
                        null
                }
        }

        private fun buildCapturedProfileText(profile: CapturedBleProfile): String {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                return buildString {
                        appendLine("BLE Repeater Lab - Captured Device Profile")
                        appendLine("Exported at: ${dateFormat.format(Date())}")
                        appendLine("=".repeat(120))
                        appendLine()

                        appendLine("[DEVICE]")
                        appendLine("name=${profile.deviceName ?: ""}")
                        appendLine("address=${profile.deviceAddress}")
                        appendLine("lastRssi=${profile.lastRssi}")
                        appendLine("capturedAt=${dateFormat.format(Date(profile.capturedAt))}")
                        appendLine()

                        appendLine("[ADVERTISING]")
                        appendLine(
                                "advertiseServiceUuids=${profile.advertiseServiceUuids.joinToString(", ")}"
                        )

                        appendLine()
                        appendLine("[MANUFACTURER_DATA]")
                        appendLine("manufacturerDataCount=${profile.manufacturerData.size}")
                        profile.manufacturerData.forEach { (manufacturerId, bytes) ->
                                val hex = BlePayloadUtils.bytesToHex(bytes).uppercase(Locale.US)
                                appendLine(
                                        "manufacturerData[$manufacturerId].lengthBytes=${bytes.size}"
                                )
                                appendLine(
                                        "manufacturerData[$manufacturerId].hex=${if (hex.isBlank()) "" else "0x$hex"}"
                                )
                                appendLine(
                                        "manufacturerData[$manufacturerId].utf8=${BlePayloadUtils.tryUtf8Preview(bytes)}"
                                )
                        }

                        appendLine()
                        appendLine("[SERVICE_DATA]")
                        appendLine("serviceDataCount=${profile.serviceData.size}")
                        profile.serviceData.forEach { (serviceUuid, bytes) ->
                                val hex = BlePayloadUtils.bytesToHex(bytes).uppercase(Locale.US)
                                appendLine("serviceData[$serviceUuid].lengthBytes=${bytes.size}")
                                appendLine(
                                        "serviceData[$serviceUuid].hex=${if (hex.isBlank()) "" else "0x$hex"}"
                                )
                                appendLine(
                                        "serviceData[$serviceUuid].utf8=${BlePayloadUtils.tryUtf8Preview(bytes)}"
                                )
                        }

                        appendLine()
                        appendLine("[GATT]")
                        appendLine("serviceCount=${profile.services.size}")
                        appendLine()

                        profile.services.forEachIndexed { serviceIndex, service ->
                                appendLine("SERVICE[$serviceIndex]")
                                appendLine("  uuid=${service.uuid}")
                                appendLine("  type=${service.type}")
                                appendLine("  characteristicCount=${service.characteristics.size}")

                                service.characteristics.forEachIndexed { charIndex, char ->
                                        val charHex =
                                                BlePayloadUtils.bytesToHex(char.value)
                                                        .uppercase(Locale.US)
                                        val charLength = char.value?.size ?: 0

                                        appendLine()
                                        appendLine("  CHARACTERISTIC[$serviceIndex.$charIndex]")
                                        appendLine("    uuid=${char.uuid}")
                                        appendLine("    rawProperties=${char.properties}")
                                        appendLine(
                                                "    permissionsForServer=${char.permissionsForServer}"
                                        )
                                        appendLine("    canRead=${char.canRead}")
                                        appendLine("    canWrite=${char.canWrite}")
                                        appendLine(
                                                "    canWriteNoResponse=${char.canWriteNoResponse}"
                                        )
                                        appendLine("    canNotify=${char.canNotify}")
                                        appendLine("    canIndicate=${char.canIndicate}")

                                        appendLine("    [VALUE]")
                                        appendLine("    valueLengthBytes=$charLength")
                                        appendLine(
                                                "    valueHex=${if (charHex.isBlank()) "" else "0x$charHex"}"
                                        )
                                        appendLine(
                                                "    valueUtf8=${BlePayloadUtils.tryUtf8Preview(char.value)}"
                                        )

                                        appendLine("    descriptorCount=${char.descriptors.size}")

                                        char.descriptors.forEachIndexed { descIndex, desc ->
                                                val descHex =
                                                        BlePayloadUtils.bytesToHex(desc.value)
                                                                .uppercase(Locale.US)
                                                val descLength = desc.value?.size ?: 0

                                                appendLine()
                                                appendLine(
                                                        "    DESCRIPTOR[$serviceIndex.$charIndex.$descIndex]"
                                                )
                                                appendLine("      uuid=${desc.uuid}")

                                                appendLine("      [VALUE]")
                                                appendLine("      valueLengthBytes=$descLength")
                                                appendLine(
                                                        "      valueHex=${if (descHex.isBlank()) "" else "0x$descHex"}"
                                                )
                                                appendLine(
                                                        "      valueUtf8=${BlePayloadUtils.tryUtf8Preview(desc.value)}"
                                                )
                                        }
                                }

                                appendLine()
                                appendLine("-".repeat(120))
                        }
                }
        }

        private fun fileTime(): String {
                return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }
}
