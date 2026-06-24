package com.camscroll.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.camscroll.service.FaceTrackingService
import com.camscroll.ui.LaunchActivity

/**
 * Quick Settings Tile — the primary toggle for CamScroll.
 *
 * ON  → starts FaceTrackingService (via LaunchActivity trampoline, Android 14 requirement)
 * OFF → stops FaceTrackingService directly
 *
 * The tile icon and label auto-sync with the service running state.
 */
class CamScrollTileService : TileService() {

    override fun onStartListening() {
        syncTileState()
    }

    override fun onClick() {
        val tile = qsTile ?: return
        if (FaceTrackingService.isRunning) {
            // Stop: can do this directly from TileService
            stopService(Intent(this, FaceTrackingService::class.java))
            tile.state = Tile.STATE_INACTIVE
            tile.updateTile()
        } else {
            // Start: must go through a visible Activity (Android 14 camera restriction)
            val launchIntent = Intent(this, LaunchActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: startActivityAndCollapse(Intent) is deprecated, use PendingIntent
                val pi = PendingIntent.getActivity(
                    this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launchIntent)
            }
        }
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        tile.state = if (FaceTrackingService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
}
