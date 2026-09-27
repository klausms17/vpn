package com.klausms.vpn.service

import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.data.Downloader
import kotlinx.serialization.json.JsonArray

/**
 * A [CoreHandle] for JVM tests, where libxray cannot load. Only the
 * calls a check or a search makes are faked; the answers may change
 * while a test runs. Single-threaded, as the tests' dispatchers are.
 */
internal class FakeCore(delays: Map<String, Long> = emptyMap()) : CoreHandle {
    /** What [measureDelay] answers per URL; a missing URL throws, as a site that does not answer. */
    val delays: MutableMap<String, Long> = delays.toMutableMap()

    /** (url, timeoutMs) of every [measureDelay], in order. */
    val measured = mutableListOf<Pair<String, Int>>()

    /** Runs at each [measureDelay] before it answers, as a test's network change during the test. */
    var onMeasure: () -> Unit = {}

    /** What [probe] answers per server (its outbounds); a missing one does not answer (-1). */
    val probeDelays = mutableMapOf<JsonArray, Long>()

    /** The candidates of every [probe], in order. */
    val probed = mutableListOf<List<JsonArray>>()

    /** Runs at each [probe] before it answers. */
    var onProbe: () -> Unit = {}

    /** The error [fetchThroughTunnel] throws, or null when the download works. */
    var fetchError: String? = null

    /** The URL of every [fetchThroughTunnel], in order. */
    val fetched = mutableListOf<String>()

    override fun start(config: String, tunFd: Int) = Unit

    override fun stop() = Unit

    override fun measureDelay(url: String, timeoutMs: Int): Long {
        measured += url to timeoutMs
        onMeasure()
        return delays[url] ?: throw Exception("$url does not answer")
    }

    override fun fetchThroughTunnel(url: String, userAgent: String, headers: String, timeoutMs: Int) {
        fetched += url
        fetchError?.let { throw Exception(it) }
    }

    override fun probe(candidates: List<JsonArray>, timeoutMs: Int, parallel: Int): List<Long> {
        probed += candidates
        onProbe()
        return candidates.map { probeDelays[it] ?: -1L }
    }

    override fun downloader(): Downloader = throw UnsupportedOperationException("not faked")
}
