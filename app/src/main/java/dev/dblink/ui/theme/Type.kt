package dev.dblink.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LeicaDisplay = FontFamily.Serif
private val LeicaText = FontFamily.SansSerif
private val LeicaData = FontFamily.Monospace

val CameraTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = LeicaDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.2.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = LeicaDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 31.sp,
        letterSpacing = 0.2.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = LeicaDisplay,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 27.sp,
        letterSpacing = 0.15.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = LeicaText,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.1.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = LeicaText,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = LeicaText,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.15.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = LeicaText,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = LeicaText,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = LeicaText,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = LeicaData,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.8.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = LeicaData,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = LeicaData,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp,
    ),
)
