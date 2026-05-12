package com.duongtc.blerepeaterlab.model

/**
 * Model đại diện cho một thiết bị BLE quét được.
 */
data class BleScanItem(
    val id: String, // Thường dùng address làm id
    val name: String?,
    val address: String,
    val rssi: Int,
    val serviceUuids: List<String> = emptyList(),
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
    val rawAdvertiseHex: String? = null,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BleScanItem
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
