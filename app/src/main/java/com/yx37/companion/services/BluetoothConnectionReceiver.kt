package com.yx37.companion.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.widget.Toast
import com.yx37.companion.audio.AudioDspManager
import com.yx37.companion.data.PreferencesManager
import com.yx37.companion.data.Yx37DeviceState

class BluetoothConnectionReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        } ?: return

        val address = device.address
        val name = try { device.name } catch (e: SecurityException) { null }

        if (!Yx37DeviceState.isMatchingDevice(address, name)) {
            return
        }

        val prefs = PreferencesManager(context)

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                prefs.isConnected = true
                val battery = readBatteryLevel(device, intent)
                if (battery in 0..100) {
                    prefs.lastBatteryLevel = battery
                }

                // Activate EQ and ensure service is active
                AudioDspManager.setHeadsetConnection(true)
                AudioDspService.start(context)

                val batteryStr = if (prefs.lastBatteryLevel >= 0) " (Pin: ${prefs.lastBatteryLevel}%)" else ""
                Toast.makeText(
                    context,
                    "YX37 Đã kết nối$batteryStr",
                    Toast.LENGTH_SHORT
                ).show()

                updateTiles(context)
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                prefs.isConnected = false
                AudioDspManager.setHeadsetConnection(false)
                updateTiles(context)
            }

            "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" -> {
                val battery = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                if (battery in 0..100) {
                    prefs.lastBatteryLevel = battery
                    updateTiles(context)
                }
            }
        }
    }

    private fun readBatteryLevel(device: BluetoothDevice, intent: Intent): Int {
        val extraBattery = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
        if (extraBattery in 0..100) return extraBattery

        return try {
            val method = device.javaClass.getMethod("getBatteryLevel")
            val level = method.invoke(device) as? Int ?: -1
            level
        } catch (e: Exception) {
            -1
        }
    }

    private fun updateTiles(context: Context) {
        try {
            TileService.requestListeningState(
                context,
                ComponentName(context, BatteryTileService::class.java)
            )
            TileService.requestListeningState(
                context,
                ComponentName(context, EqTileService::class.java)
            )
        } catch (e: Exception) {
            // Might throw on older OS or if service disabled
        }
    }
}
