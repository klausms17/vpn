package com.klausms.vpn.core

import android.content.Context
import libxray.Libxray

/**
 * Requests outside the tunnel (this app's own traffic never enters it):
 * they tell a block from a phone without internet, and the mobile
 * whitelist from a block. A fake in tests. Every call blocks, so call it
 * on a background dispatcher.
 */
internal interface DirectNet {
    /** Whether [url] answers at all; any HTTP status counts. No app name is sent. */
    fun opens(url: String): Boolean

    /** 1 = every address of [host] is on the Russian mobile whitelist, 0 = none, -1 = some. */
    fun whitelistStatus(host: String): Int

    companion object {
        /**
         * A Russian site, opened outside the tunnel: tells "servers blocked"
         * from "no internet", and the mobile whitelist from a block. A small
         * file, not the page: this can run every few minutes while nothing
         * answers.
         */
        const val DIRECT_URL = "https://ya.ru/robots.txt"
    }
}

/**
 * Whether the network seems to let through only the mobile operator's
 * whitelist: outside the tunnel a foreign site does not open while a
 * Russian one does. A block of the servers alone would leave the foreign
 * site open. Only meaningful on mobile data. Asks the foreign site first;
 * [russianOpens] is what the caller already knows about the Russian one,
 * which is asked last when null. Blocking.
 */
internal fun DirectNet.onlyWhitelistOpens(russianOpens: Boolean? = null): Boolean =
    !opens(XrayCore.TEST_URL) && (russianOpens ?: opens(DirectNet.DIRECT_URL))

/** [DirectNet] through the Go core's own HTTP client. Stateless and thread-safe. */
internal class XrayDirectNet(context: Context) : DirectNet {
    private val appContext = context.applicationContext

    override fun opens(url: String): Boolean = try {
        Libxray.fetchWithHeaders(url, "", "", DIRECT_TIMEOUT_MS, "")
        true
    } catch (ex: Exception) {
        // Any answer at all (a captcha, a refusal) proves the site can be reached.
        CoreErrors.httpStatus(ex.message) != null
    }

    override fun whitelistStatus(host: String): Int = XrayCore.whitelistStatus(appContext, host)

    private companion object {
        const val DIRECT_TIMEOUT_MS = 5_000
    }
}
