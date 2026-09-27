package com.klausms.vpn.service

import com.klausms.vpn.core.DirectNet
import java.util.Collections

/**
 * A [DirectNet] for JVM tests. [opening]: the URLs that open outside the
 * tunnel. [statuses]: the whitelist status per host; a host missing from
 * it fails. Every URL opened is recorded in [asked].
 */
internal class FakeDirect(
    val opening: MutableSet<String> = mutableSetOf(),
    val statuses: MutableMap<String, Int> = mutableMapOf(),
) : DirectNet {
    val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Runs at each [opens] before it answers, as a test's network change during the request. */
    var onOpen: () -> Unit = {}

    override fun opens(url: String): Boolean {
        asked += url
        onOpen()
        return url in opening
    }

    override fun whitelistStatus(host: String): Int = statuses[host] ?: throw Exception("lookup failed")
}
