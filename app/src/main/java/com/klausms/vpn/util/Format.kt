package com.klausms.vpn.util

import java.util.Locale

fun formatSpeed(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/с"

fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0)
    return when {
        b < 1024 -> "$b Б"
        b < 1024 * 1024 -> String.format(Locale.US, "%.1f КБ", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f МБ", b / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f ГБ", b / (1024.0 * 1024 * 1024))
    }
}

fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}
