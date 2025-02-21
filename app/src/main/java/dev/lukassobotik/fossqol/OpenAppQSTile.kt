package dev.lukassobotik.fossqol

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class OpenAppQSTile: TileService() {
    data class StateModel(val enabled: Boolean, val label: String, val icon: Icon)

    // Called when the user adds your tile.
    override fun onTileAdded() {
        super.onTileAdded()
        qsTile.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.open_foss_qol)
            updateTile()
        }
    }
    // Called when your app can update your tile.
    override fun onStartListening() {
        super.onStartListening()
        qsTile.apply {
            state = Tile.STATE_INACTIVE
            label = getString(R.string.open_foss_qol)
            updateTile()
        }
    }

    // Called when your app can no longer update your tile.
    override fun onStopListening() {
        super.onStopListening()
    }

    // Called when the user taps on your tile in an active or inactive state.
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivityAndCollapse(pendingIntent)
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(intent)
        }
    }
    // Called when the user removes your tile.
    override fun onTileRemoved() {
        super.onTileRemoved()
    }
}