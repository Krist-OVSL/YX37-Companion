package com.yx37.companion.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("yx37_companion_prefs", Context.MODE_PRIVATE)

    var isEqEnabled: Boolean
        get() = prefs.getBoolean(KEY_EQ_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_EQ_ENABLED, value).apply()

    var selectedPreset: String
        get() = prefs.getString(KEY_SELECTED_PRESET, "Flat") ?: "Flat"
        set(value) = prefs.edit().putString(KEY_SELECTED_PRESET, value).apply()

    var isAutoRestoreEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESTORE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RESTORE, value).apply()

    var lastBatteryLevel: Int
        get() = prefs.getInt(KEY_LAST_BATTERY, -1)
        set(value) = prefs.edit().putInt(KEY_LAST_BATTERY, value).apply()

    var isConnected: Boolean
        get() = prefs.getBoolean(KEY_IS_CONNECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_CONNECTED, value).apply()

    var connectedDeviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value).apply()

    var connectedDeviceAddress: String?
        get() = prefs.getString(KEY_DEVICE_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ADDRESS, value).apply()

    val isEqInitialized: Boolean
        get() = prefs.getBoolean(KEY_EQ_INITIALIZED, false)

    fun getBandGain(index: Int): Float {
        return prefs.getFloat(KEY_BAND_PREFIX + index, 0f)
    }

    fun getCustomEqBands(numBands: Int): FloatArray {
        if (!isEqInitialized) {
            return FloatArray(numBands) { 0f }
        }
        val result = FloatArray(numBands)
        for (i in 0 until numBands) {
            result[i] = prefs.getFloat(KEY_BAND_PREFIX + i, 0f)
        }
        return result
    }

    fun saveCustomEqBands(bands: FloatArray) {
        val editor = prefs.edit()
        editor.putBoolean(KEY_EQ_INITIALIZED, true)
        for (i in bands.indices) {
            editor.putFloat(KEY_BAND_PREFIX + i, bands[i])
        }
        editor.apply()
    }

    fun resetEqBands(numBands: Int): FloatArray {
        val flat = FloatArray(numBands) { 0f }
        saveCustomEqBands(flat)
        selectedPreset = "Flat"
        return flat
    }

    companion object {
        private const val KEY_EQ_ENABLED = "key_eq_enabled"
        private const val KEY_SELECTED_PRESET = "key_selected_preset"
        private const val KEY_AUTO_RESTORE = "key_auto_restore"
        private const val KEY_LAST_BATTERY = "key_last_battery"
        private const val KEY_IS_CONNECTED = "key_is_connected"
        private const val KEY_DEVICE_NAME = "key_device_name"
        private const val KEY_DEVICE_ADDRESS = "key_device_address"
        private const val KEY_EQ_INITIALIZED = "key_eq_initialized"
        private const val KEY_BAND_PREFIX = "key_eq_band_"
    }
}
