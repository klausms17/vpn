package com.klausms.vpn.data

/**
 * How good a measured server delay is. The one place for the boundaries:
 * the app's server rows and the widget each draw a grade with their own
 * bars and colours.
 */
enum class PingGrade { GREAT, GOOD, FAIR, POOR }

/** The grade of a delay of [ms] milliseconds. */
fun pingGrade(ms: Long): PingGrade = when {
    ms < 150 -> PingGrade.GREAT
    ms < 400 -> PingGrade.GOOD
    ms < 1000 -> PingGrade.FAIR
    else -> PingGrade.POOR
}
