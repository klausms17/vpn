package com.klausms.vpn.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.klausms.vpn.R

/**
 * The app's look follows the newest iOS (Liquid Glass) in its dark
 * appearance: iOS system colours, SF-like type (Inter, the closest open font
 * with Cyrillic), inset-grouped lists and glass controls.
 */
@Immutable
data class KlausColors(
    val page: Color = Color(0xFF000000),
    val label: Color = Color(0xFFFFFFFF),
    val secondary: Color = Color(0x99EBEBF5),
    val tertiary: Color = Color(0x4DEBEBF5),
    val quaternary: Color = Color(0x2EEBEBF5),
    val green: Color = Color(0xFF30D158),
    val orange: Color = Color(0xFFFF9230),
    val red: Color = Color(0xFFFF453A),
    val blue: Color = Color(0xFF0A84FF),
    val gray: Color = Color(0xFF8E8E93),
    val card: Color = Color(0xFF1C1C1E),
    val cardPressed: Color = Color(0xFF2C2C2E),
    val separator: Color = Color(0xA6545458),
    /** tertiarySystemFill: text fields, capsule buttons. */
    val fill: Color = Color(0x3D767680),
    val cta: Color = Color(0xFF1F8038),
    val switchOff: Color = Color(0x52787880),
)

val LocalKlausColors = staticCompositionLocalOf { KlausColors() }

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** The iOS text styles (pt = sp), with Inter's tracking tuned per size. */
object IosType {
    private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
        fontFamily = Inter,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = tracking.em,
    )

    val largeTitle = style(34, 41, FontWeight.Bold)
    val title2 = style(22, 28, FontWeight.Bold, -0.01)
    val title3 = style(20, 25, FontWeight.SemiBold, -0.01)
    val headline = style(17, 22, FontWeight.SemiBold, -0.015)
    val body = style(17, 22, FontWeight.Normal, -0.015)
    val callout = style(16, 21, FontWeight.Normal, -0.015)
    val subhead = style(15, 20, FontWeight.Normal, -0.013)
    val subheadStrong = style(15, 20, FontWeight.SemiBold, -0.013)
    val footnote = style(13, 18, FontWeight.Normal)
    val caption = style(12, 16, FontWeight.Normal)
    val tab = style(10, 12, FontWeight.Medium, 0.01)
    val timer = TextStyle(
        fontFamily = Inter,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.01).em,
        fontFeatureSettings = "tnum",
    )
}

private val materialColors = darkColorScheme(
    primary = Color(0xFF30D158),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFF0A84FF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0x99EBEBF5),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xA6545458),
    error = Color(0xFFFF453A),
)

private val materialType = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = Inter),
        displayMedium = t.displayMedium.copy(fontFamily = Inter),
        displaySmall = t.displaySmall.copy(fontFamily = Inter),
        headlineLarge = t.headlineLarge.copy(fontFamily = Inter),
        headlineMedium = t.headlineMedium.copy(fontFamily = Inter),
        headlineSmall = t.headlineSmall.copy(fontFamily = Inter),
        titleLarge = IosType.title3,
        titleMedium = IosType.headline,
        titleSmall = IosType.subheadStrong,
        bodyLarge = IosType.body,
        bodyMedium = IosType.subhead,
        bodySmall = IosType.footnote,
        labelLarge = IosType.subheadStrong,
        labelMedium = IosType.footnote,
        labelSmall = IosType.caption,
    )
}

@Composable
fun KlausTheme(content: @Composable () -> Unit) {
    // Always the dark appearance: it is the approved design, and the widget
    // and the notification match it.
    MaterialTheme(colorScheme = materialColors, typography = materialType) {
        androidx.compose.runtime.CompositionLocalProvider(LocalKlausColors provides KlausColors(), content = content)
    }
}

val kc: KlausColors
    @Composable get() = LocalKlausColors.current
