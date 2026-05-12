package com.duongtc.blerepeaterlab.model

/**
 * Model đại diện cho một GATT Characteristic đã capture.
 */
data class CapturedGattCharacteristic(
    val uuid: String,
    val properties: Int,
    val permissionsForServer: Int, // Quyền cho GATT Server giả lập
    val value: ByteArray? = null,
    val descriptors: List<CapturedGattDescriptor> = emptyList(),
    val canRead: Boolean = false,
    val canWrite: Boolean = false,
    val canWriteNoResponse: Boolean = false,
    val canNotify: Boolean = false,
    val canIndicate: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CapturedGattCharacteristic
        return uuid == other.uuid
    }

    override fun hashCode(): Int {
        return uuid.hashCode()
    }
}
