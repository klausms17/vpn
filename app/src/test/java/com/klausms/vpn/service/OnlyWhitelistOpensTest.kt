package com.klausms.vpn.service

import com.klausms.vpn.core.DirectNet
import com.klausms.vpn.core.XrayCore
import com.klausms.vpn.core.onlyWhitelistOpens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlyWhitelistOpensTest {
    private val foreign = XrayCore.TEST_URL
    private val russian = DirectNet.DIRECT_URL

    @Test
    fun onlyTheRussianSiteOpening() {
        val direct = FakeDirect(opening = mutableSetOf(russian))
        assertTrue(direct.onlyWhitelistOpens())
        assertEquals(listOf(foreign, russian), direct.asked)
    }

    @Test
    fun aForeignSiteThatOpensEndsTheQuestion() {
        val direct = FakeDirect(opening = mutableSetOf(foreign, russian))
        assertFalse(direct.onlyWhitelistOpens())
        assertEquals(listOf(foreign), direct.asked)
    }

    @Test
    fun nothingOpeningIsNoWhitelist() {
        val direct = FakeDirect()
        assertFalse(direct.onlyWhitelistOpens())
        assertEquals(listOf(foreign, russian), direct.asked)
    }

    @Test
    fun whatIsKnownOfTheRussianSiteIsNotAskedAgain() {
        val direct = FakeDirect()
        assertTrue(direct.onlyWhitelistOpens(russianOpens = true))
        assertFalse(direct.onlyWhitelistOpens(russianOpens = false))
        assertEquals(listOf(foreign, foreign), direct.asked)
    }
}
