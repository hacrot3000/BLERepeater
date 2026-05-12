package com.duongtc.blerepeaterlab.model

/**
 * Model đại diện cho một dòng log BLE.
 */
data class BleLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val source: LogSource,
    val operation: LogOperation,
    val serviceUuid: String? = null,
    val characteristicUuid: String? = null,
    val descriptorUuid: String? = null,
    val payloadHex: String? = null,
    val statusCode: Int? = null,
    val message: String
)

enum class LogSource {
    SCANNER,
    REAL_GATT_CLIENT,
    FAKE_GATT_SERVER,
    ADVERTISER,
    SYSTEM
}

enum class LogOperation {
    SCAN_RESULT,
    CONNECT,
    DISCONNECT,
    DISCOVER_SERVICE,
    READ_CHAR,
    READ_DESC,
    WRITE_CHAR,
    WRITE_DESC,
    NOTIFY,
    INDICATE,
    START_ADVERTISE,
    STOP_ADVERTISE,
    ERROR
}
