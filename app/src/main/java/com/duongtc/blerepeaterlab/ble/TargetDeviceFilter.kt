package com.duongtc.blerepeaterlab.ble

import android.bluetooth.le.ScanResult

/**
 * Class xử lý logic lọc thiết bị BLE.
 */
object TargetDeviceFilter {

    /**
     * Kiểm tra xem thiết bị quét được có phải là thiết bị mục tiêu không.
     * Hiện tại luôn trả về true để quét tất cả.
     */
    fun isTargetDevice(result: ScanResult): Boolean {
        // TODO: Bổ sung rule lọc thiết bị thật ở đây sau này:
        // - Filter theo service UUID
        // - Filter theo manufacturerData
        // - Filter theo device name
        // - Filter theo frameNumber
        
        return true
    }
}
