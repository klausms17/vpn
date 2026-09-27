package com.klausms.vpn.service

/**
 * The notices that outlive the moment they are shown next to "Подключено":
 * the [base] hint, and the "switched to another server" notice with the
 * time it went up, so that a later check can take it away. Plus the rule
 * for when a notice may replace the one on screen.
 *
 * Owns no status: the caller reads the one on screen and applies the
 * answers. Thread-safe as far as each field goes (they are volatile and
 * read or written one at a time), from the main thread, the start queue
 * and background checks alike.
 */
internal class NoticeBoard {
    /** A hint shown whenever no other notice is (strict Private DNS); null when none applies. */
    @Volatile
    var base: String? = null

    @Volatile
    private var switchNotice: String? = null

    // When switchNotice went up (elapsed time).
    @Volatile
    private var switchNoticeAt = 0L

    /** A start has just put [notice] on screen at [now]: a "switched to another server" notice, or null. */
    fun switched(notice: String?, now: Long) {
        switchNotice = notice
        switchNoticeAt = now
    }

    /**
     * The "switched to another server" notice to take away now, or null:
     * it is still on screen ([currentMessage]) and at least [minAgeMs] old
     * at [now]. Once another notice has replaced it, it is forgotten, so it
     * never comes back.
     */
    fun switchNoticeToClear(currentMessage: String?, now: Long, minAgeMs: Long): String? {
        val notice = switchNotice ?: return null
        if (currentMessage != notice) {
            switchNotice = null
            return null
        }
        return notice.takeIf { now - switchNoticeAt >= minAgeMs }
    }

    companion object {
        /**
         * Whether [proposed] (null: no notice) goes up in place of [current]:
         * it differs, and [current] is one of [replacing] when that is given
         * (null in it stands for no notice on screen).
         */
        fun shouldReplace(current: String?, proposed: String?, replacing: Set<String?>?): Boolean =
            current != proposed && (replacing == null || current in replacing)
    }
}
