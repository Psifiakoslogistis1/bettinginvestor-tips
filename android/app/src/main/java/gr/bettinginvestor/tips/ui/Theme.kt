package gr.bettinginvestor.tips.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Deep navy/black + vivid emerald + bright gold — no olive/khaki. */
object BiColors {
    val Bg = Color(0xFF05070F)
    val BgElevated = Color(0xFF0B1020)
    val Card = Color(0xFF121A2B)
    val CardAlt = Color(0xFF1A2438)
    val Gold = Color(0xFFFFD54F)
    val GoldDim = Color(0xFFFFC107)
    val Green = Color(0xFF00E676)
    val GreenSoft = Color(0xFF00C853)
    val Text = Color(0xFFF5F7FA)
    val TextMuted = Color(0xFF9AA3B5)
    val Win = Color(0xFF00E676)
    val Loss = Color(0xFFFF5252)
    val Stroke = Color(0xFF2A3548)
    val Navy = Color(0xFF151C2E)
}

private val scheme = darkColorScheme(
    primary = BiColors.Gold,
    onPrimary = BiColors.Bg,
    secondary = BiColors.Green,
    onSecondary = BiColors.Bg,
    tertiary = BiColors.GreenSoft,
    background = BiColors.Bg,
    onBackground = BiColors.Text,
    surface = BiColors.Card,
    onSurface = BiColors.Text,
    surfaceVariant = BiColors.CardAlt,
    onSurfaceVariant = BiColors.TextMuted,
    outline = BiColors.Stroke,
    error = BiColors.Loss,
)

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 28.sp, color = BiColors.Text),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, color = BiColors.TextMuted),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun TipsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
}
