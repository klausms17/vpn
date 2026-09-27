package com.klausms.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PingGradeTest {
    @Test
    fun gradesChangeAt150And400And1000Milliseconds() {
        assertEquals(PingGrade.GREAT, pingGrade(0))
        assertEquals(PingGrade.GREAT, pingGrade(149))
        assertEquals(PingGrade.GOOD, pingGrade(150))
        assertEquals(PingGrade.GOOD, pingGrade(399))
        assertEquals(PingGrade.FAIR, pingGrade(400))
        assertEquals(PingGrade.FAIR, pingGrade(999))
        assertEquals(PingGrade.POOR, pingGrade(1000))
        assertEquals(PingGrade.POOR, pingGrade(8000))
    }
}
