package com.klausms.vpn.util

import java.util.Locale

fun formatSpeed(bytesPerSecond: Long): String = formatBytes(bytesPerSecond) + "/с"

/** "512 Б", "12,4 ГБ", "100 ГБ": Russian decimal comma, no trailing ",0". */
fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0)
    val k = 1024.0
    return when {
        b < 1024 -> "$b Б"
        b < k * k -> amount(b / k, "КБ")
        b < k * k * k -> amount(b / (k * k), "МБ")
        b < k * k * k * k -> amount(b / (k * k * k), "ГБ")
        else -> amount(b / (k * k * k * k), "ТБ")
    }
}

private fun amount(value: Double, unit: String): String {
    val number = if (value >= 100) {
        Math.round(value).toString()
    } else {
        String.format(Locale.US, "%.1f", value).removeSuffix(".0").replace('.', ',')
    }
    return "$number $unit"
}

fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}
