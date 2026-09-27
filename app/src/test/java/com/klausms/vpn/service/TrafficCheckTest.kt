package com.klausms.vpn.service

import com.klausms.vpn.core.XrayCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficCheckTest {
    private val google = XrayCore.TEST_URL
    private val cloudflare = XrayCore.TEST_URL_ALT

    @Test
    fun googleAnsweringIsEnough() {
        val core = FakeCore(mapOf(google to 120L, cloudflare to 90L))
        assertTrue(TrafficCheck.passes(core, primaryMs = 6_000, confirmMs = 4_000))
        assertEquals(listOf(google to 6_000), core.measured)
    }

    @Test
    fun cloudflareConfirmsWhenGoogleFails() {
        val core = FakeCore(mapOf(cloudflare to 90L))
        assertTrue(TrafficCheck.passes(core, primaryMs = 6_000, confirmMs = 4_000))
        assertEquals(listOf(google to 6_000, cloudflare to 4_000), core.measured)
    }

    @Test
    fun aNegativeDelayIsNoAnswer() {
        val core = FakeCore(mapOf(google to -1L, cloudflare to -1L))
        assertFalse(TrafficCheck.passes(core, primaryMs = 4_000, confirmMs = 4_000))
        assertEquals(listOf(google to 4_000, cloudflare to 4_000), core.measured)
    }

    @Test
    fun neitherAnsweringFails() {
        assertFalse(TrafficCheck.passes(FakeCore(), primaryMs = 6_000, confirmMs = 4_000))
    }

    @Test
    fun theDelayIsGooglesOrElseCloudflares() {
        assertEquals(120L, TrafficCheck.delay(FakeCore(mapOf(google to 120L, cloudflare to 90L)), primaryMs = 8_000))
        val core = FakeCore(mapOf(cloudflare to 90L))
        assertEquals(90L, TrafficCheck.delay(core, primaryMs = 8_000))
        // Cloudflare gets the second opinion's timeout, whatever the first site had.
        assertEquals(listOf(google to 8_000, cloudflare to TrafficCheck.CONFIRM_TIMEOUT_MS), core.measured)
    }

    @Test
    fun theDelayThrowsWhenNeitherAnswers() {
        val core = FakeCore()
        val error = runCatching { TrafficCheck.delay(core, primaryMs = 8_000) }.exceptionOrNull()
        assertTrue(error is Exception)
        assertEquals(2, core.measured.size)
    }
}
