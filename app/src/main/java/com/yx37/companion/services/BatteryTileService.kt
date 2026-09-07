package com.yx37.companion.services

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.yx37.companion.MainActivity
import com.yx37.companion.data.PreferencesManager

class BatteryTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(intent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val prefs = PreferencesManager(this)

        if (prefs.isConnected) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "YX37"
            val pct = prefs.lastBatteryLevel
            val subtitle = if (pct >= 0) "$pct%" else "Đã kết nối"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = subtitle
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "YX37"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Ngắt kết nối"
            }
        }

        tile.updateTile()
    }
}
