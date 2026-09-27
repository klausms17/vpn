package com.klausms.vpn.service

import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.data.ProfilesAccess
import com.klausms.vpn.data.StoredProfile
import com.klausms.vpn.util.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [TunnelControl] without a TUN interface. A start brings up the
 * server it asks for (a switch's winner, else the selection in
 * [profiles]) on [core] and then calls [onUp], as the engine's listener
 * hears it; with [startsFail] it leaves the tunnel as it was. Resets go
 * through a real [ResetScheduler] and record their reason in [resetsDone].
 * Everything runs in [scope] on [dispatcher], one block at a time.
 */
internal class FakeTunnel(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    epoch: Epoch,
    private val clock: Clock,
    private val profiles: ProfilesAccess,
    private val core: CoreHandle,
) : TunnelControl {
    override var session: TunnelSession? = null
    var lastStartId = 1
    var startsFail = false
    var onUp: suspend (TunnelSession, StartRequest) -> Unit = { _, _ -> }
    val starts = mutableListOf<StartRequest>()
    val resetsDone = mutableListOf<String>()

    private val serial = Mutex()
    private val resets = ResetScheduler(epoch, scope, dispatcher)

    /** The tunnel runs [profile], connected just now. */
    fun runOn(profile: StoredProfile): TunnelSession =
        TunnelSession(profile, config = "{}", core = core, connectedAt = clock.elapsed(), lockdownConflict = false).also { session = it }

    override fun latestStartId(): Int = lastStartId

    override fun submit(block: suspend () -> Unit): Job = scope.launch(dispatcher) { serial.withLock { block() } }

    override suspend fun start(req: StartRequest) {
        starts += req
        if (startsFail) return
        val saved = profiles.snapshot()
        val profile = req.switch?.let { switch -> saved.profiles.firstOrNull { it.id == switch.winnerId } } ?: saved.selected ?: return
        onUp(runOn(profile), req)
    }

    override fun resetInPlace(why: String, delayMs: Long, expectedEpoch: Long?): Boolean =
        resets.schedule(delayMs, expectedEpoch) { resetsDone += why }
}
