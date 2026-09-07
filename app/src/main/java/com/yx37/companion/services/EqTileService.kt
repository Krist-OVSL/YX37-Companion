package com.yx37.companion.services

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.yx37.companion.audio.AudioDspManager
import com.yx37.companion.data.PreferencesManager

class EqTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val prefs = PreferencesManager(this)
        val newState = !prefs.isEqEnabled
        prefs.isEqEnabled = newState
        AudioDspManager.setMasterEnabled(newState, this)

        val message = if (newState) {
            "YX37 EQ: BẬT"
        } else {
            "YX37 EQ: TẮT"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val prefs = PreferencesManager(this)

        tile.label = "YX37 EQ"

        if (prefs.isEqEnabled) {
            tile.state = Tile.STATE_ACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = prefs.selectedPreset
            }
        } else {
            tile.state = Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "Đã tắt"
            }
        }

        tile.updateTile()
    }
}
