package com.folio.hermeslive.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Folio's palette (copied from :app ui/Theme.kt; Hermes Live does not depend on :app).
val PrintInk = Color(0xFFE8E0D4)
val PrintMute = Color(0xFF8A847C)
val VoidBlack = Color(0xFF050607)
val Surface = Color(0xFF101216)
val SurfaceHigh = Color(0xFF181B20)
val Hairline = Color(0x33E8E0D4)

/** The one restrained accent: a warm ember for Hermes's voice. */
val Ember = Color(0xFFD9A066)

/** End call: a muted brick red, not a siren. */
val EndRed = Color(0xFFB4483C)

val PrintShadow = Shadow(color = Color.Black.copy(alpha = 0.84f), offset = Offset(0f, 1.6f), blurRadius = 0f)

private val Crisp = PlatformTextStyle(includeFontPadding = false)

val TitleStyle = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 22.sp, letterSpacing = 0.3.sp, platformStyle = Crisp)
val StatusStyle = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Light, fontSize = 24.sp, lineHeight = 30.sp, platformStyle = Crisp)
val HeadingStyle = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 18.sp, platformStyle = Crisp)
val CaptionStyle = TextStyle(fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 24.sp, platformStyle = Crisp)
val BodyStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 21.sp, platformStyle = Crisp)
val SmallStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.4.sp, platformStyle = Crisp)
val LabelStyle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.2.sp, platformStyle = Crisp)
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, platformStyle = Crisp)

private val LiveColors = darkColorScheme(
    primary = PrintInk,
    onPrimary = VoidBlack,
    secondary = Ember,
    onSecondary = VoidBlack,
    background = VoidBlack,
    onBackground = PrintInk,
    surface = Surface,
    onSurface = PrintInk,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = PrintMute,
    outline = Hairline,
    error = EndRed,
)

@Composable
fun LiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LiveColors, content = content)
}
