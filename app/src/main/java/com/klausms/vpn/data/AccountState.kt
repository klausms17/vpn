package com.klausms.vpn.data

import kotlinx.serialization.Serializable

/**
 * What the app keeps about its account. The session token is its only
 * secret, kept like the servers' keys and links: in the app's own files,
 * which backups leave out. The password is never kept.
 */
@Serializable
data class AccountState(
    val email: String = "",
    val token: String = "",
    val status: AccountStatus = AccountStatus.SIGNED_OUT,
    /** The subscription made from the account's link. */
    val subscriptionId: String? = null,
    /** When the service last answered about the account. */
    val checkedAt: Long = 0,
) {
    val signedIn: Boolean get() = token.isNotEmpty()

    /**
     * Time to ask the service again: often while the owner has not decided,
     * or while the access granted has no servers here yet (their download
     * failed), else hourly. A clock set back makes it due.
     */
    fun due(now: Long): Boolean {
        if (!signedIn) return false
        val soon = status == AccountStatus.PENDING || status == AccountStatus.ACTIVE && subscriptionId == null
        return now - checkedAt >= (if (soon) PENDING_EVERY_MS else CHECK_EVERY_MS) || now < checkedAt
    }

    /** The state after the service answered about the account. */
    fun with(account: Account, now: Long): AccountState = copy(email = account.email, status = account.status, checkedAt = now)

    companion object {
        const val PENDING_EVERY_MS = 5 * 60_000L
        const val CHECK_EVERY_MS = 60 * 60_000L
    }
}
