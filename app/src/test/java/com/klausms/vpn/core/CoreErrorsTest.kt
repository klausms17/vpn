package com.klausms.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreErrorsTest {
    @Test
    fun theCoresHttpErrorsGiveTheirStatus() {
        assertEquals(403, CoreErrors.httpStatus("HTTP 403 Forbidden"))
        assertEquals(503, CoreErrors.httpStatus("HTTP 503 Service Unavailable"))
        assertEquals(502, CoreErrors.httpStatus("HTTP 502"))
        assertNull(CoreErrors.httpStatus("dial tcp: i/o timeout"))
        assertNull(CoreErrors.httpStatus("HTTP/2 stream error"))
        assertNull(CoreErrors.httpStatus("HTTP 50"))
        assertNull(CoreErrors.httpStatus(null))
    }
}
