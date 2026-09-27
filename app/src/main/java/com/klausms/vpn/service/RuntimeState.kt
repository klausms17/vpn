package com.klausms.vpn.service

import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import com.klausms.vpn.util.ProcessExits

/**
 * Small state private to the VPN process: whether the tunnel should be up
 * (for restarts after the process was killed), a crash-loop guard, the
 * budgets of automatic server switches and searches, and the user's server
 * while an automatic switch keeps the tunnel on another one.
 */
internal object RuntimeState {
    private const val PREFS = "vpn_runtime"

    fun shouldRun(context: Context) = prefs(context).getBoolean("should_run", false)

    fun setShouldRun(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean("should_run", value) }
    }

    /**
     * Whether the user has allowed this VPN (a tunnel came up once). Lets the
     * widget connect directly without calling VpnService.prepare(), which is
     * not a query: with an earlier consent it takes the VPN over from
     * whichever app is running one.
     */
    fun vpnConsented(context: Context) = prefs(context).getBoolean("vpn_consented", false)

    fun setVpnConsented(context: Context, value: Boolean) {
        if (vpnConsented(context) != value) prefs(context).edit { putBoolean("vpn_consented", value) }
    }

    /**
     * Whether an automatic switch to another server is allowed now (see
     * [Failover.MAX_SWITCHES]); [take] counts one. Kept on disk so a
     * restarted process cannot start over; by time since boot, so a clock
     * change cannot either.
     */
    fun allowFailover(context: Context, take: Boolean = true): Boolean {
        val p = prefs(context)
        val next = Failover.countSwitch(p.getString("failovers", "") ?: "", SystemClock.elapsedRealtime()) ?: return false
        if (take) p.edit { putString("failovers", next) }
        return true
    }

    /** Whether an automatic search for another server is allowed now (see [Failover.MAX_SEARCHES]); counts one. */
    fun allowSearch(context: Context): Boolean {
        val p = prefs(context)
        val next = Failover.countSearch(p.getString("searches", "") ?: "", SystemClock.elapsedRealtime()) ?: return false
        p.edit { putString("searches", next) }
        return true
    }

    /**
     * Whether the system may restart the tunnel after the process ended:
     * at most 3 times in 5 minutes after crashes (see [RestartGuard]).
     * Android 11+ tells why the process ended (App logs it); on older ones
     * every end counts. By time since boot, so a clock change cannot fool it.
     */
    fun allowAutoRestart(context: Context): Boolean {
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ProcessExits.lastVpnExit(context)?.reason else null
        if (reason != null && !RestartGuard.isCrash(reason)) return true
        val p = prefs(context)
        val next = RestartGuard.countRestart(p.getString("restarts", "") ?: "", SystemClock.elapsedRealtime()) ?: return false
        p.edit { putString("restarts", next) }
        return true
    }

    fun away(context: Context): Failover.Away? {
        val p = prefs(context)
        val home = p.getString("away_home", null) ?: return null
        val to = p.getString("away_to", null) ?: return null
        return Failover.Away(home, to, p.getLong("away_retry", 0), p.getInt("away_backoff", 0))
    }

    fun setAway(context: Context, away: Failover.Away?) {
        prefs(context).edit {
            if (away == null) {
                remove("away_home")
                remove("away_to")
                remove("away_retry")
                remove("away_backoff")
            } else {
                putString("away_home", away.home)
                putString("away_to", away.to)
                putLong("away_retry", away.retryAt)
                putInt("away_backoff", away.backoff)
            }
        }
    }

    fun returned(context: Context): Failover.Returned? {
        val p = prefs(context)
        val id = p.getString("returned_id", null) ?: return null
        return Failover.Returned(id, p.getLong("returned_at", 0), p.getInt("returned_backoff", 0))
    }

    fun setReturned(context: Context, returned: Failover.Returned) {
        prefs(context).edit {
            putString("returned_id", returned.id)
            putLong("returned_at", returned.at)
            putInt("returned_backoff", returned.backoff)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * The part of [RuntimeState] the tunnel's collaborators use, so tests can
 * give them a fake. Thread-safe: every call goes to SharedPreferences.
 */
internal interface RuntimeStore {
    fun shouldRun(): Boolean

    fun setShouldRun(value: Boolean)

    fun setVpnConsented(value: Boolean)

    /** See [RuntimeState.allowFailover]. */
    fun allowFailover(take: Boolean = true): Boolean

    /** See [RuntimeState.allowSearch]. */
    fun allowSearch(): Boolean

    fun away(): Failover.Away?

    fun setAway(away: Failover.Away?)

    fun returned(): Failover.Returned?

    fun setReturned(returned: Failover.Returned)
}

/** [RuntimeStore] on the app's own preferences, through [RuntimeState]. */
internal class PrefsRuntimeStore(context: Context) : RuntimeStore {
    private val context = context.applicationContext

    override fun shouldRun() = RuntimeState.shouldRun(context)

    override fun setShouldRun(value: Boolean) = RuntimeState.setShouldRun(context, value)

    override fun setVpnConsented(value: Boolean) = RuntimeState.setVpnConsented(context, value)

    override fun allowFailover(take: Boolean) = RuntimeState.allowFailover(context, take)

    override fun allowSearch() = RuntimeState.allowSearch(context)

    override fun away() = RuntimeState.away(context)

    override fun setAway(away: Failover.Away?) = RuntimeState.setAway(context, away)

    override fun returned() = RuntimeState.returned(context)

    override fun setReturned(returned: Failover.Returned) = RuntimeState.setReturned(context, returned)
}
