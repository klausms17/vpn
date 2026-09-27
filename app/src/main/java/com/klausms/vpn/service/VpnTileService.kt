package com.klausms.vpn.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.klausms.vpn.R
import com.klausms.vpn.ui.MainActivity
import com.klausms.vpn.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile. Lives in the VPN process to read the status directly. */
class VpnTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        watch?.cancel()
        watch = scope.launch { VpnStatusHolder.status.collect { render(it) } }
    }

    override fun onStopListening() {
        watch?.cancel()
        watch = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        when (VpnStatusHolder.status.value.state) {
            // Shown as on (see render), so a tap turns it off, as on the widget.
            VpnState.CONNECTED, VpnState.CONNECTING -> VpnCommands.disconnect(this, "tile")
            // Going down already; the tile is greyed out then.
            VpnState.DISCONNECTING -> Unit
            else -> {
                // prepare() throws while an old built-in VPN (PPTP, L2TP) is
                // set as always-on with blocking: the app explains it.
                val needsApp = try {
                    VpnService.prepare(this) != null
                } catch (e: Exception) {
                    AppLog.w("tile: vpn prepare", e)
                    true
                }
                if (needsApp) {
                    // First run: the permission dialog needs the app.
                    openApp()
                } else {
                    try {
                        VpnCommands.connect(this)
                    } catch (e: Exception) {
                        AppLog.w("tile could not start the VPN", e)
                        openApp()
                    }
                }
            }
        }
    }

    // The Intent overload is the only option below Android 14.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_CONNECT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(status: VpnStatus) {
        val tile = qsTile ?: return
        // Connecting counts as on: the start can take seconds (and retries
        // longer), and a tile that still looks off invites a second tap,
        // which would turn it off.
        tile.state = when (status.state) {
            VpnState.CONNECTED, VpnState.CONNECTING -> Tile.STATE_ACTIVE
            VpnState.DISCONNECTING -> Tile.STATE_UNAVAILABLE
            VpnState.ERROR, VpnState.DISCONNECTED -> Tile.STATE_INACTIVE
        }
        val progress = when (status.state) {
            VpnState.CONNECTED -> status.profileName
            VpnState.CONNECTING -> "Подключение…"
            VpnState.DISCONNECTING -> "Отключение…"
            VpnState.ERROR -> "Ошибка"
            VpnState.DISCONNECTED -> "Выключен"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = progress
        } else {
            // No subtitle before Android 10: the passing states go in the label.
            tile.label = when (status.state) {
                VpnState.CONNECTING, VpnState.DISCONNECTING, VpnState.ERROR -> progress
                else -> getString(R.string.tile_label)
            }
        }
        tile.updateTile()
    }
}
