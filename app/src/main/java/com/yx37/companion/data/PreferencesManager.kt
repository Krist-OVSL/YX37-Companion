package com.yx37.companion.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CustomProfile(
    val id: String,
    val name: String,
    val gains: List<Float>
)

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

    var activeCustomProfileId: String
        get() = prefs.getString(KEY_ACTIVE_PROFILE_ID, "default_profile_1") ?: "default_profile_1"
        set(value) = prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, value).apply()

    fun getCustomProfiles(numBands: Int): List<CustomProfile> {
        val jsonStr = prefs.getString(KEY_CUSTOM_PROFILES_JSON, null)
        if (jsonStr.isNullOrEmpty()) {
            // Migrate from legacy single custom bands or default flat
            val legacyBands = getLegacyCustomEqBands(numBands)
            val defaultProfile = CustomProfile(
                id = "default_profile_1",
                name = "Tùy chỉnh 1",
                gains = legacyBands.toList()
            )
            val list = listOf(defaultProfile)
            saveCustomProfiles(list)
            return list
        }

        return try {
            val jsonArray = JSONArray(jsonStr)
            val result = mutableListOf<CustomProfile>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", "profile_$i")
                val name = obj.optString("name", "Tùy chỉnh ${i + 1}")
                val gainsArray = obj.optJSONArray("gains")
                val gains = mutableListOf<Float>()
                if (gainsArray != null) {
                    for (j in 0 until numBands) {
                        if (j < gainsArray.length()) {
                            gains.add(gainsArray.getDouble(j).toFloat())
                        } else {
                            gains.add(0f)
                        }
                    }
                } else {
                    repeat(numBands) { gains.add(0f) }
                }
                result.add(CustomProfile(id = id, name = name, gains = gains))
            }
            if (result.isEmpty()) {
                val def = CustomProfile("default_profile_1", "Tùy chỉnh 1", List(numBands) { 0f })
                listOf(def)
            } else {
                result
            }
        } catch (e: Exception) {
            val def = CustomProfile("default_profile_1", "Tùy chỉnh 1", List(numBands) { 0f })
            listOf(def)
        }
    }

    fun saveCustomProfiles(profiles: List<CustomProfile>) {
        try {
            val jsonArray = JSONArray()
            for (p in profiles) {
                val obj = JSONObject()
                obj.put("id", p.id)
                obj.put("name", p.name)
                val gainsArray = JSONArray()
                for (g in p.gains) {
                    gainsArray.put(g.toDouble())
                }
                obj.put("gains", gainsArray)
                jsonArray.put(obj)
            }
            prefs.edit().putString(KEY_CUSTOM_PROFILES_JSON, jsonArray.toString()).apply()
        } catch (e: Exception) {
            // ignore
        }
    }

    fun getActiveCustomProfile(numBands: Int): CustomProfile {
        val list = getCustomProfiles(numBands)
        val activeId = activeCustomProfileId
        return list.find { it.id == activeId } ?: list.firstOrNull() ?: CustomProfile(
            "default_profile_1",
            "Tùy chỉnh 1",
            List(numBands) { 0f }
        )
    }

    fun addCustomProfile(name: String, gains: FloatArray, numBands: Int): CustomProfile {
        val list = getCustomProfiles(numBands).toMutableList()
        val newId = "profile_" + UUID.randomUUID().toString().take(8)
        val cleanName = if (name.trim().isEmpty()) "Tùy chỉnh ${list.size + 1}" else name.trim()
        val newProfile = CustomProfile(id = newId, name = cleanName, gains = gains.toList())
        list.add(newProfile)
        saveCustomProfiles(list)
        activeCustomProfileId = newId
        selectedPreset = "Tùy chỉnh"
        return newProfile
    }

    fun updateActiveProfileGains(gains: FloatArray, numBands: Int) {
        val list = getCustomProfiles(numBands).toMutableList()
        val activeId = activeCustomProfileId
        val index = list.indexOfFirst { it.id == activeId }
        val newGainsList = gains.toList()
        if (index >= 0) {
            list[index] = list[index].copy(gains = newGainsList)
        } else {
            list.add(CustomProfile(activeId, "Tùy chỉnh", newGainsList))
        }
        saveCustomProfiles(list)
        // Also save to legacy keys for compatibility with tiles
        saveLegacyCustomEqBands(gains)
    }

    fun deleteCustomProfile(profileId: String, numBands: Int): List<CustomProfile> {
        val list = getCustomProfiles(numBands).toMutableList()
        list.removeAll { it.id == profileId }
        if (list.isEmpty()) {
            val def = CustomProfile("default_profile_1", "Tùy chỉnh 1", List(numBands) { 0f })
            list.add(def)
        }
        saveCustomProfiles(list)
        if (activeCustomProfileId == profileId || list.none { it.id == activeCustomProfileId }) {
            activeCustomProfileId = list.first().id
        }
        return list
    }

    fun renameCustomProfile(profileId: String, newName: String, numBands: Int): List<CustomProfile> {
        val list = getCustomProfiles(numBands).toMutableList()
        val index = list.indexOfFirst { it.id == profileId }
        if (index >= 0) {
            val cleanName = if (newName.trim().isEmpty()) list[index].name else newName.trim()
            list[index] = list[index].copy(name = cleanName)
            saveCustomProfiles(list)
        }
        return list
    }

    // Legacy methods for tiles and service compatibility
    fun getCustomEqBands(numBands: Int): FloatArray {
        val active = getActiveCustomProfile(numBands)
        val result = FloatArray(numBands)
        for (i in 0 until numBands) {
            result[i] = if (i < active.gains.size) active.gains[i] else 0f
        }
        return result
    }

    fun saveCustomEqBands(bands: FloatArray) {
        updateActiveProfileGains(bands, bands.size)
    }

    private fun getLegacyCustomEqBands(numBands: Int): FloatArray {
        if (!isEqInitialized) {
            return FloatArray(numBands) { 0f }
        }
        val result = FloatArray(numBands)
        for (i in 0 until numBands) {
            result[i] = prefs.getFloat(KEY_BAND_PREFIX + i, 0f)
        }
        return result
    }

    private fun saveLegacyCustomEqBands(bands: FloatArray) {
        val editor = prefs.edit()
        editor.putBoolean(KEY_EQ_INITIALIZED, true)
        for (i in bands.indices) {
            editor.putFloat(KEY_BAND_PREFIX + i, bands[i])
        }
        editor.apply()
    }

    fun resetEqBands(numBands: Int): FloatArray {
        val flat = FloatArray(numBands) { 0f }
        updateActiveProfileGains(flat, numBands)
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
        private const val KEY_CUSTOM_PROFILES_JSON = "key_custom_profiles_json"
        private const val KEY_ACTIVE_PROFILE_ID = "key_active_profile_id"
    }
}
