package com.duongtc.blerepeaterlab.model

/**
 * Model đại diện cho một GATT Descriptor đã capture.
 */
data class CapturedGattDescriptor(
    val uuid: String,
    val value: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CapturedGattDescriptor
        return uuid == other.uuid
    }

    override fun hashCode(): Int {
        return uuid.hashCode()
    }
}
