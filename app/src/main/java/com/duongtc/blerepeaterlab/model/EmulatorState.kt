package com.duongtc.blerepeaterlab.model

/**
 * Enum đại diện cho trạng thái của Emulator.
 */
enum class EmulatorState {
    IDLE,
    STARTING_ADVERTISER,
    ADVERTISING,
    GATT_SERVER_RUNNING,
    CLIENT_CONNECTED,
    ERROR
}
