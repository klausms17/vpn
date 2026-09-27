package com.klausms.vpn.core

/** Reads the Go core's error messages. Plain Kotlin: tests need no native library. */
object CoreErrors {
    private val HTTP_STATUS = Regex("""^HTTP (\d{3})\b""")

    /** The status of an "HTTP 503 Service Unavailable" error from the core, or null for other errors. */
    fun httpStatus(message: String?): Int? =
        HTTP_STATUS.find(message ?: return null)?.groupValues?.get(1)?.toInt()
}
