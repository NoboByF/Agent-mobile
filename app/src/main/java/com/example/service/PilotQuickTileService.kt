package com.example.service

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.PilotStateHub

/**
 * Android Quick Settings Tile allowing users to launch or stop a LivePilot AI voice call
 * directly from the notification shade over any app.
 */
class PilotQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        PilotStateHub.ensureInitialized(applicationContext)
        refreshTileVisual()
    }

    override fun onClick() {
        super.onClick()
        PilotStateHub.ensureInitialized(applicationContext)

        if (PilotStateHub.sessionState.value.isServiceRunning) {
            FloatingCallOverlayService.stop(applicationContext)
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                subtitle = "Tap to start"
                updateTile()
            }
            return
        }

        val hasKey = PilotStateHub.settings.value.hasAnyApiKey
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasKey && hasMic) {
            FloatingCallOverlayService.start(applicationContext)
            qsTile?.apply {
                state = Tile.STATE_ACTIVE
                subtitle = "Call Active"
                updateTile()
            }
        } else {
            val openApp = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = android.app.PendingIntent.getActivity(
                    this,
                    101,
                    openApp,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(openApp)
            }
        }
    }

    private fun refreshTileVisual() {
        val active = PilotStateHub.sessionState.value.isServiceRunning
        qsTile?.apply {
            state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "LivePilot AI"
            subtitle = if (active) "Call Active" else "Start Voice AI"
            updateTile()
        }
    }
}
