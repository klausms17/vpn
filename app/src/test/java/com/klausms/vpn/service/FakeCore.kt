package com.klausms.vpn.service

import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.data.Downloader
import kotlinx.serialization.json.JsonArray

/**
 * A [CoreHandle] for JVM tests, where libxray cannot load. [delays] is
 * what [measureDelay] answers per URL (a missing URL throws, as a site
 * that does not answer); every call is recorded in [measured].
 */
internal class FakeCore(private val delays: Map<String, Long> = emptyMap()) : CoreHandle {
    /** (url, timeoutMs) of every [measureDelay], in order. */
    val measured = mutableListOf<Pair<String, Int>>()

    override fun start(config: String, tunFd: Int) = Unit

    override fun stop() = Unit

    override fun measureDelay(url: String, timeoutMs: Int): Long {
        measured += url to timeoutMs
        return delays[url] ?: throw Exception("$url does not answer")
    }

    override fun fetchThroughTunnel(url: String, userAgent: String, headers: String, timeoutMs: Int) =
        throw UnsupportedOperationException("not faked")

    override fun probe(candidates: List<JsonArray>, timeoutMs: Int, parallel: Int): List<Long> =
        throw UnsupportedOperationException("not faked")

    override fun downloader(): Downloader = throw UnsupportedOperationException("not faked")
}
