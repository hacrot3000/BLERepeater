package com.duongtc.blerepeaterlab.model

/**
 * Model chứa toàn bộ thông tin đã capture được từ thiết bị thật.
 */
data class CapturedBleProfile(
    val deviceName: String?,
    val deviceAddress: String,
    val lastRssi: Int,
    val advertiseServiceUuids: List<String> = emptyList(),
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
    val services: List<CapturedGattService> = emptyList(),
    val capturedAt: Long = System.currentTimeMillis()
)
