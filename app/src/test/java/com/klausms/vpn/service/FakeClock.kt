package com.klausms.vpn.service

import com.klausms.vpn.util.Clock

/** A [Clock] that tests set by hand: [elapsedMs] since boot, [wallMs] by the phone's clock. */
internal class FakeClock(var elapsedMs: Long = 0, var wallMs: Long = 0) : Clock {
    override fun elapsed(): Long = elapsedMs

    override fun wall(): Long = wallMs
}
