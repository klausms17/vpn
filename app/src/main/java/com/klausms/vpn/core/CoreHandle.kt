package com.klausms.vpn.core

import com.klausms.vpn.data.Downloader
import kotlinx.serialization.json.JsonArray
import libxray.Controller
import libxray.Libxray

/**
 * The tunnel's Xray core, as the VPN service drives it, so the logic
 * around it can be tested with a fake. Every call blocks: [start] and
 * [stop] run where the service starts and stops the tunnel, the rest on a
 * background dispatcher, never on the main thread. Errors arrive as
 * exceptions whose message is meant for the user.
 */
internal interface CoreHandle {
    /** Starts the core with [config], reading the TUN interface [tunFd]. */
    fun start(config: String, tunFd: Int)

    /** Stops the core; does nothing when it never started. */
    fun stop()

    /** The delay to [url] through the running core in ms; throws when it does not answer. */
    fun measureDelay(url: String, timeoutMs: Int): Long

    /** Downloads [url] through the running core's server and drops the body; throws when that fails. */
    fun fetchThroughTunnel(url: String, userAgent: String, headers: String, timeoutMs: Int)

    /** The delay through each candidate in ms, or -1, in the same order (see [XrayCore.probe]). */
    fun probe(candidates: List<JsonArray>, timeoutMs: Int, parallel: Int): List<Long>

    /** Downloads subscriptions through the running core's server (see [XrayCore.fetchThroughTunnel]). */
    fun downloader(): Downloader
}

/**
 * [CoreHandle] over the gomobile core, the only holder of its Controller.
 * The Controller is created at the first [start]: the first touch of
 * libxray loads the Go runtime, which must never happen on the main thread
 * (in onCreate or onDestroy). Only [start] writes it; any thread may read it.
 */
internal class XrayCoreHandle : CoreHandle {
    @Volatile
    private var controller: Controller? = null

    override fun start(config: String, tunFd: Int) {
        (controller ?: Libxray.newController().also { controller = it }).start(config, tunFd)
    }

    override fun stop() {
        controller?.stop()
    }

    override fun measureDelay(url: String, timeoutMs: Int): Long = started().measureDelay(url, timeoutMs)

    override fun fetchThroughTunnel(url: String, userAgent: String, headers: String, timeoutMs: Int) {
        started().fetchThroughTunnel(url, userAgent, headers, timeoutMs)
    }

    override fun probe(candidates: List<JsonArray>, timeoutMs: Int, parallel: Int): List<Long> =
        XrayCore.probe(started(), candidates, timeoutMs = timeoutMs, parallel = parallel)

    override fun downloader(): Downloader {
        val c = started()
        return Downloader { url, headers -> XrayCore.fetchThroughTunnel(c, url, headers) }
    }

    private fun started(): Controller = checkNotNull(controller) { "core is not running" }
}
