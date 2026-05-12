package com.duongtc.blerepeaterlab.ble

import android.util.SparseArray
import java.nio.charset.StandardCharsets

/**
 * Tiện ích xử lý dữ liệu byte/hex cho BLE.
 */
object BlePayloadUtils {

    /**
     * Chuyển đổi mảng byte sang chuỗi Hex.
     */
    fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null) return ""
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = "0123456789ABCDEF"[v ushr 4]
            hexChars[i * 2 + 1] = "0123456789ABCDEF"[v and 0x0F]
        }
        return String(hexChars)
        // Thêm khoảng trắng giữa các byte cho dễ đọc nếu cần:
        // return hexChars.asSequence().chunked(2).map { it.joinToString("") }.joinToString(" ")
    }

    /**
     * Thử decode mảng byte sang UTF-8 text nếu có thể in được.
     */
    fun tryUtf8Preview(bytes: ByteArray?): String? {
        if (bytes == null) return null
        try {
            val str = String(bytes, StandardCharsets.UTF_8)
            // Kiểm tra xem chuỗi có chứa ký tự không in được không
            if (str.all { it.isLetterOrDigit() || it.isWhitespace() || it in ".,-_:;()[]{}" }) {
                return str
            }
        } catch (e: Exception) {
            // Không decode được
        }
        return null
    }

    /**
     * Chuyển đổi SparseArray sang Map.
     */
    fun <T> sparseArrayToMap(sparseArray: SparseArray<T>?): Map<Int, T> {
        if (sparseArray == null) return emptyMap()
        val map = mutableMapOf<Int, T>()
        for (i in 0 until sparseArray.size()) {
            map[sparseArray.keyAt(i)] = sparseArray.valueAt(i)
        }
        return map
    }

    /**
     * Parse Manufacturer Data thành chuỗi hex dễ đọc.
     */
    fun parseManufacturerData(data: Map<Int, ByteArray>): String {
        if (data.isEmpty()) return "None"
        return data.entries.joinToString(", ") { (id, bytes) ->
            "ID: 0x${Integer.toHexString(id).uppercase()} -> 0x${bytesToHex(bytes)}"
        }
    }

    /**
     * Parse Service Data thành chuỗi hex dễ đọc.
     */
    fun parseServiceData(data: Map<String, ByteArray>): String {
        if (data.isEmpty()) return "None"
        return data.entries.joinToString(", ") { (uuid, bytes) ->
            "UUID: $uuid -> 0x${bytesToHex(bytes)}"
        }
    }
}
