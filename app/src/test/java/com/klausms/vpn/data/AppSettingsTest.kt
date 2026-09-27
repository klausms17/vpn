package com.klausms.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun settingsSavedByOlderVersionsStillDecode() {
        // Keys of settings removed since are skipped, not an error: the choices still in use survive.
        val old = """{"mode":"global","ipv6":true,"appMode":"only_selected","bypassRussianApps":false,""" +
            """"excludedApps":["ru.sberbankmobile"],"includedApps":["org.telegram.messenger"],"directRules":["domain:example.com"],""" +
            """"proxyRules":[],"blockRules":[],"resetOnNetworkChange":false,"verboseLog":true}"""
        assertEquals(
            AppSettings(bypassRussianApps = false, excludedApps = setOf("ru.sberbankmobile")),
            AppJson.decodeFromString(AppSettings.serializer(), old),
        )
    }
}
