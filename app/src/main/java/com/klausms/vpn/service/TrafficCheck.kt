package com.klausms.vpn.service

import com.klausms.vpn.core.CoreHandle
import com.klausms.vpn.core.XrayCore

/**
 * Whether traffic gets through the running core. One site failing is not
 * enough: some servers cannot reach Google but carry everything else, so
 * Cloudflare has the second word. Stateless; every call blocks, so call it
 * on a background dispatcher.
 */
internal object TrafficCheck {
    /** How long the background check waits for its first site. */
    const val VERIFY_TIMEOUT_MS = 6_000

    /** How long a second opinion waits (and both sites once a server answered again). */
    const val CONFIRM_TIMEOUT_MS = 4_000

    /** Whether Google answers through [core] within [primaryMs], or else Cloudflare within [confirmMs]. Never throws. */
    fun passes(core: CoreHandle, primaryMs: Int, confirmMs: Int): Boolean =
        answers(core, XrayCore.TEST_URL, primaryMs) || answers(core, XrayCore.TEST_URL_ALT, confirmMs)

    /**
     * The delay through [core] in ms: Google within [primaryMs], else
     * Cloudflare within [CONFIRM_TIMEOUT_MS]. Throws when neither answers.
     */
    fun delay(core: CoreHandle, primaryMs: Int): Long {
        val ms = try {
            core.measureDelay(XrayCore.TEST_URL, primaryMs)
        } catch (_: Exception) {
            -1L
        }
        return if (ms >= 0) ms else core.measureDelay(XrayCore.TEST_URL_ALT, CONFIRM_TIMEOUT_MS)
    }

    private fun answers(core: CoreHandle, url: String, timeoutMs: Int): Boolean = try {
        core.measureDelay(url, timeoutMs) >= 0
    } catch (_: Exception) {
        false
    }
}
