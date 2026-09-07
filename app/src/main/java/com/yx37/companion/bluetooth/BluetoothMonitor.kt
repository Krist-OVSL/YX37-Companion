package com.yx37.companion.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.yx37.companion.data.PreferencesManager
import com.yx37.companion.data.Yx37DeviceState

data class DeviceStatusResult(
    val isConnected: Boolean = false,
    val batteryLevel: Int = -1,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val isPermissionGranted: Boolean = true,
    val statusMessage: String = ""
)

class BluetoothMonitor(private val context: Context) {

    private val prefs = PreferencesManager(context)
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter ?: try {
        @Suppress("DEPRECATION")
        BluetoothAdapter.getDefaultAdapter()
    } catch (t: Throwable) {
        null
    }

    private var a2dpProxy: BluetoothA2dp? = null
    private var headsetProxy: BluetoothHeadset? = null

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = proxy as? BluetoothA2dp
                BluetoothProfile.HEADSET -> headsetProxy = proxy as? BluetoothHeadset
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = null
                BluetoothProfile.HEADSET -> headsetProxy = null
            }
        }
    }

    fun start() {
        if (!hasBluetoothPermission()) return
        try {
            adapter?.let {
                it.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
                it.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET)
            }
        } catch (t: Throwable) {
            Log.w("BluetoothMonitor", "Could not bind profile proxy: ${t.message}")
        }
    }

    fun stop() {
        try {
            adapter?.let {
                a2dpProxy?.let { p -> it.closeProfileProxy(BluetoothProfile.A2DP, p) }
                headsetProxy?.let { p -> it.closeProfileProxy(BluetoothProfile.HEADSET, p) }
            }
        } catch (t: Throwable) {
            // ignore
        }
        a2dpProxy = null
        headsetProxy = null
    }

    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    fun checkStatus(): DeviceStatusResult {
        val permGranted = hasBluetoothPermission()

        if (!permGranted) {
            val isAudioOut = checkAudioManagerRouting()
            return DeviceStatusResult(
                isConnected = isAudioOut,
                batteryLevel = prefs.lastBatteryLevel,
                deviceName = if (isAudioOut) "YX37 (Audio Active)" else null,
                deviceAddress = if (isAudioOut) Yx37DeviceState.TARGET_MAC_ADDRESS else null,
                isPermissionGranted = false,
                statusMessage = "Cần cấp quyền 'Thiết bị ở gần' (Nearby devices)"
            )
        }

        val isBtEnabled = try {
            adapter?.isEnabled == true
        } catch (t: Throwable) {
            false
        }

        if (adapter == null || !isBtEnabled) {
            prefs.isConnected = false
            return DeviceStatusResult(
                isConnected = false,
                batteryLevel = prefs.lastBatteryLevel,
                isPermissionGranted = true,
                statusMessage = "Bluetooth trên điện thoại đang TẮT"
            )
        }

        // 1. Check Profile Proxies (A2DP & HEADSET)
        val activeDevices = mutableListOf<BluetoothDevice>()
        try {
            a2dpProxy?.connectedDevices?.let { activeDevices.addAll(it) }
            headsetProxy?.connectedDevices?.let { activeDevices.addAll(it) }
        } catch (t: Throwable) {
            // ignore
        }

        for (device in activeDevices) {
            val name = try { device.name } catch (t: Throwable) { null }
            val addr = try { device.address } catch (t: Throwable) { null }
            if (Yx37DeviceState.isMatchingDevice(addr, name)) {
                val battery = readBattery(device)
                if (battery in 0..100) {
                    prefs.lastBatteryLevel = battery
                }
                prefs.isConnected = true
                prefs.connectedDeviceName = name ?: "YX37"
                prefs.connectedDeviceAddress = addr ?: Yx37DeviceState.TARGET_MAC_ADDRESS
                return DeviceStatusResult(
                    isConnected = true,
                    batteryLevel = prefs.lastBatteryLevel,
                    deviceName = prefs.connectedDeviceName,
                    deviceAddress = prefs.connectedDeviceAddress,
                    isPermissionGranted = true,
                    statusMessage = "Đã kết nối (A2DP/HFP)"
                )
            }
        }

        // 2. Check Bonded Devices
        try {
            val bonded = adapter.bondedDevices
            if (bonded != null) {
                for (device in bonded) {
                    val name = try { device.name } catch (t: Throwable) { null }
                    val addr = try { device.address } catch (t: Throwable) { null }
                    if (Yx37DeviceState.isMatchingDevice(addr, name)) {
                        val isConn = isDeviceConnected(device)
                        val battery = readBattery(device)
                        if (battery in 0..100) {
                            prefs.lastBatteryLevel = battery
                        }

                        val a2dpState = try { adapter.getProfileConnectionState(BluetoothProfile.A2DP) } catch (t: Throwable) { -1 }
                        val headsetState = try { adapter.getProfileConnectionState(BluetoothProfile.HEADSET) } catch (t: Throwable) { -1 }
                        val isProfileConnected = a2dpState == BluetoothProfile.STATE_CONNECTED ||
                                                 headsetState == BluetoothProfile.STATE_CONNECTED

                        if (isConn || isProfileConnected) {
                            prefs.isConnected = true
                            prefs.connectedDeviceName = name ?: "YX37"
                            prefs.connectedDeviceAddress = addr ?: Yx37DeviceState.TARGET_MAC_ADDRESS
                            return DeviceStatusResult(
                                isConnected = true,
                                batteryLevel = prefs.lastBatteryLevel,
                                deviceName = prefs.connectedDeviceName,
                                deviceAddress = prefs.connectedDeviceAddress,
                                isPermissionGranted = true,
                                statusMessage = "Đã kết nối"
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // ignore
        }

        // 3. Fallback: AudioManager routing
        if (checkAudioManagerRouting()) {
            prefs.isConnected = true
            return DeviceStatusResult(
                isConnected = true,
                batteryLevel = prefs.lastBatteryLevel,
                deviceName = prefs.connectedDeviceName ?: "YX37",
                deviceAddress = prefs.connectedDeviceAddress ?: Yx37DeviceState.TARGET_MAC_ADDRESS,
                isPermissionGranted = true,
                statusMessage = "Đang phát âm thanh qua Bluetooth"
            )
        }

        prefs.isConnected = false
        return DeviceStatusResult(
            isConnected = false,
            batteryLevel = prefs.lastBatteryLevel,
            isPermissionGranted = true,
            statusMessage = "Chưa kết nối tai nghe YX37"
        )
    }

    private fun checkAudioManagerRouting(): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (d in devices) {
                if (d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    val pName = d.productName?.toString() ?: ""
                    if (Yx37DeviceState.isMatchingDevice(null, pName)) {
                        return true
                    }
                }
            }
            false
        } catch (t: Throwable) {
            false
        }
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("isConnected")
            (method.invoke(device) as? Boolean) ?: false
        } catch (t: Throwable) {
            false
        }
    }

    private fun readBattery(device: BluetoothDevice): Int {
        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            (method.invoke(device) as? Int) ?: -1
        } catch (t: Throwable) {
            -1
        }
    }
}
