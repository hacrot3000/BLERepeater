package com.duongtc.blerepeaterlab.model

/**
 * Model đại diện cho một GATT Service đã capture.
 */
data class CapturedGattService(
    val uuid: String,
    val type: Int?, // BluetoothGattService.SERVICE_TYPE_PRIMARY hoặc SECONDARY
    val characteristics: List<CapturedGattCharacteristic> = emptyList()
)
