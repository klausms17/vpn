package com.klausms.vpn.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.klausms.vpn.service.IVpnCallback
import com.klausms.vpn.service.IVpnController
import com.klausms.vpn.service.TrafficStats
import com.klausms.vpn.service.VpnState
import com.klausms.vpn.service.VpnStatus
import com.klausms.vpn.service.XrayVpnService
import com.klausms.vpn.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The UI side of the connection to the VPN process. Bound only while the
 * app is on screen, so the VPN process sends nothing when nobody looks.
 */
class VpnClient(private val context: Context) {
    private companion object {
        const val REBIND_GAP_MS = 10_000L
    }

    private val _status = MutableStateFlow(VpnStatus())
    val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _fresh = MutableStateFlow(false)

    /**
     * [status] is the VPN process's own. False from [unbind] (the app left
     * the screen: the tunnel may have changed since) until the process says
     * its state again after [bind], which takes a moment.
     */
    val fresh: StateFlow<Boolean> = _fresh.asStateFlow()

    private val _traffic = MutableStateFlow(TrafficStats())
    val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

    private val _profilesChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The VPN process saved other servers or a new selection: reload them. */
    val profilesChanged: SharedFlow<Unit> = _profilesChanged.asSharedFlow()

    @Volatile
    private var controller: IVpnController? = null
    private var bound = false
    private val main = Handler(Looper.getMainLooper())
    private var lastRebind = -REBIND_GAP_MS

    private val callback = object : IVpnCallback.Stub() {
        override fun onStatus(state: Int, profileId: String?, profileName: String?, message: String?, connectedSince: Long) {
            val s = VpnState.of(state)
            _status.value = VpnStatus(s, profileId, profileName, message, connectedSince)
            _fresh.value = true
            if (s != VpnState.CONNECTED) _traffic.value = TrafficStats()
        }

        override fun onTraffic(upRate: Long, downRate: Long, upTotal: Long, downTotal: Long) {
            _traffic.value = TrafficStats(upRate, downRate, upTotal, downTotal)
        }

        override fun onProfilesChanged() {
            _profilesChanged.tryEmit(Unit)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val c = IVpnController.Stub.asInterface(service)
            controller = c
            try {
                c.registerCallback(callback)
            } catch (e: Exception) {
                AppLog.w("register callback", e)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // The VPN process died; Android tears the tunnel down with it.
            AppLog.w("vpn process died")
            controller = null
            // Known, not guessed: no process, no tunnel.
            _status.value = VpnStatus()
            _fresh.value = true
            _traffic.value = TrafficStats()
            // Android restarts a crashed service only after a pause (up to
            // half an hour after repeated crashes); binding anew brings it
            // back now, and it resumes the tunnel if it should run.
            // At most every 10 s: a process that dies on start must not loop.
            val now = SystemClock.elapsedRealtime()
            if (now - lastRebind < REBIND_GAP_MS) return
            lastRebind = now
            main.post {
                if (bound) {
                    unbind()
                    bind()
                }
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            unbind()
            bind()
        }
    }

    fun bind() {
        if (bound) return
        bound = context.bindService(
            Intent(context, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_BIND),
            connection,
            Context.BIND_AUTO_CREATE,
        )
        // No answer is coming: what is shown is the best there is.
        if (!bound) _fresh.value = true
    }

    fun unbind() {
        if (!bound) return
        try {
            controller?.unregisterCallback(callback)
        } catch (_: Exception) {
        }
        try {
            context.unbindService(connection)
        } catch (_: IllegalArgumentException) {
        }
        controller = null
        bound = false
        _fresh.value = false
    }

    /** Latency through the running tunnel in ms, or -1. */
    suspend fun testConnection(): Long = withContext(Dispatchers.IO) {
        try {
            controller?.testConnection() ?: -1
        } catch (_: Exception) {
            -1
        }
    }
}
