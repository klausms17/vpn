package com.klausms.vpn.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticeBoardTest {
    private val switched = Failover.switchedNotice("Б", "А")

    @Test
    fun anyOtherNoticeReplacesWithoutAList() {
        assertTrue(NoticeBoard.shouldReplace(null, Failover.NOTICE_SEARCHING, replacing = null))
        assertTrue(NoticeBoard.shouldReplace(Failover.NOTICE_SEARCHING, null, replacing = null))
        assertTrue(NoticeBoard.shouldReplace(switched, Failover.NOTICE_PICK_ANOTHER, replacing = null))
    }

    @Test
    fun theSameNoticeIsNeverShownAgain() {
        assertFalse(NoticeBoard.shouldReplace(Failover.NOTICE_SEARCHING, Failover.NOTICE_SEARCHING, replacing = null))
        assertFalse(NoticeBoard.shouldReplace(null, null, replacing = setOf(null)))
    }

    @Test
    fun withAListOnlyThoseAreReplaced() {
        val failures = Failover.FAILURE_NOTICES
        // A check that got through clears a failure, but never a switch notice.
        assertTrue(NoticeBoard.shouldReplace(Failover.NOTICE_SEARCHING, null, failures))
        assertFalse(NoticeBoard.shouldReplace(switched, null, failures))
        assertFalse(NoticeBoard.shouldReplace(null, Failover.NOTICE_PRIVATE_DNS, failures))
    }

    @Test
    fun nullInTheListStandsForNoNotice() {
        // The Private DNS hint goes up where nothing, or the old hint, is shown.
        val hintSpots = setOf(null, Failover.NOTICE_PRIVATE_DNS)
        assertTrue(NoticeBoard.shouldReplace(null, Failover.NOTICE_PRIVATE_DNS, setOf(null)))
        assertFalse(NoticeBoard.shouldReplace(Failover.NOTICE_SEARCHING, Failover.NOTICE_PRIVATE_DNS, setOf(null)))
        assertTrue(NoticeBoard.shouldReplace(Failover.NOTICE_PRIVATE_DNS, null, hintSpots))
    }

    @Test
    fun noSwitchNoticeNothingToClear() {
        val board = NoticeBoard()
        assertNull(board.switchNoticeToClear(null, now = 1_000_000, minAgeMs = 0))
        board.switched(null, now = 5)
        assertNull(board.switchNoticeToClear(null, now = 1_000_000, minAgeMs = 0))
    }

    @Test
    fun theSwitchNoticeGoesOnceOldEnough() {
        val board = NoticeBoard()
        board.switched(switched, now = 1_000)
        assertNull(board.switchNoticeToClear(switched, now = 1_000 + 599_999, minAgeMs = 600_000))
        // Too young is not forgotten: a later check takes it away.
        assertEquals(switched, board.switchNoticeToClear(switched, now = 1_000 + 600_000, minAgeMs = 600_000))
    }

    @Test
    fun anotherNetworkTakesItAwayAtOnce() {
        val board = NoticeBoard()
        board.switched(switched, now = 1_000)
        assertEquals(switched, board.switchNoticeToClear(switched, now = 1_000, minAgeMs = 0))
    }

    @Test
    fun onceReplacedItIsForgotten() {
        val board = NoticeBoard()
        board.switched(switched, now = 1_000)
        assertNull(board.switchNoticeToClear(Failover.NOTICE_SEARCHING, now = 2_000_000, minAgeMs = 0))
        // Even if the same text were on screen again, it is no longer this switch's.
        assertNull(board.switchNoticeToClear(switched, now = 2_000_000, minAgeMs = 0))
    }

    @Test
    fun aNewStartReplacesTheSwitchNotice() {
        val board = NoticeBoard()
        board.switched(switched, now = 1_000)
        board.switched(null, now = 2_000)
        assertNull(board.switchNoticeToClear(switched, now = 2_000_000, minAgeMs = 0))
    }
}
