package com.fortress.vault.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.fortress.vault.R

// Spectral (OFL-licensed, bundled as static .ttf files under res/font — see
// licenses/SPECTRAL-OFL.txt at the project root) for the "engraved plate"
// headline feel. Bundling the actual font files rather than using Compose's
// downloadable-fonts API deliberately avoids needing exact Google Fonts
// provider certificate hashes hardcoded into font_certs.xml — getting those
// wrong silently breaks font loading at runtime, whereas a bundled file just
// always works, with no network dependency either.
val SpectralFontFamily = FontFamily(
    Font(R.font.spectral_medium, FontWeight.Medium),
    Font(R.font.spectral_semibold, FontWeight.SemiBold),
    Font(R.font.spectral_bold, FontWeight.Bold)
)

val FortressTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SpectralFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        letterSpacing = 0.5.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = SpectralFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = SpectralFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 1.2.sp
    )
)

// Monospace, wide-tracked style reserved for the countdown display —
// meant to read like a mechanical vault timer, not a UI label.
val CountdownStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 56.sp,
    letterSpacing = 2.sp
)

