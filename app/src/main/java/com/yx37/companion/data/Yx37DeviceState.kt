package com.yx37.companion.data

data class Yx37DeviceState(
    val isConnected: Boolean = false,
    val batteryLevel: Int? = null,
    val isStudioNeutral: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    companion object {
        const val TARGET_MAC_ADDRESS = "D9:5A:25:CC:D3:4B"
        const val TARGET_NAME_SUBSTRING = "YX37"

        fun isMatchingDevice(address: String?, name: String?): Boolean {
            if (!address.isNullOrBlank() && address.equals(TARGET_MAC_ADDRESS, ignoreCase = true)) {
                return true
            }
            if (!name.isNullOrBlank()) {
                val clean = name.replace("-", "").replace(" ", "").replace("_", "").uppercase()
                if (clean.contains(TARGET_NAME_SUBSTRING)) {
                    return true
                }
            }
            return false
        }
    }
}
