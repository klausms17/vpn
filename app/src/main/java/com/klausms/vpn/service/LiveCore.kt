package com.klausms.vpn.service

import com.klausms.vpn.core.CoreHandle

/**
 * The running tunnel's core, for the widget's ping in the same process: a
 * second, temporary core would take over Xray's process-wide logger from
 * the tunnel. Null while no core runs. Written only by XrayVpnService,
 * right next to its session; read from any thread.
 */
internal object LiveCore {
    @Volatile
    var current: CoreHandle? = null
}
