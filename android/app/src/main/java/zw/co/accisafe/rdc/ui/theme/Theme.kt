package zw.co.accisafe.rdc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand greens, darkest to lightest.
val Forest = Color(0xFF0B3D20)
val Pine = Color(0xFF14532D)
val Emerald = Color(0xFF1B7A3E)
val Leaf = Color(0xFF2E9E5B)
val Mint = Color(0xFFD7F2E1)

// Priority and status colours.
val Critical = Color(0xFFC62828)
val High = Color(0xFFEF6C00)
val Medium = Color(0xFFF9A825)
val Low = Color(0xFF546E7A)
val Resolved = Color(0xFF2E7D32)

/** Signature diagonal green gradient used for headers and hero cards. */
val BrandGradient = Brush.linearGradient(listOf(Forest, Emerald, Leaf))
val SoftGradient = Brush.verticalGradient(listOf(Color(0xFFEAF6EE), Color(0xFFF7FAF8)))

private val LightColors = lightColorScheme(
    primary = Emerald,
    onPrimary = Color.White,
    primaryContainer = Mint,
    onPrimaryContainer = Forest,
    secondary = Pine,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EFE7),
    onSecondaryContainer = Forest,
    tertiary = Color(0xFF00695C),
    background = Color(0xFFF4F7F5),
    onBackground = Color(0xFF16201A),
    surface = Color.White,
    onSurface = Color(0xFF16201A),
    surfaceVariant = Color(0xFFE8EEEA),
    onSurfaceVariant = Color(0xFF4A5A50),
    surfaceContainer = Color(0xFFF0F4F1),
    surfaceContainerHigh = Color(0xFFE9EFEB),
    outline = Color(0xFFB8C6BD),
    error = Critical,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FD39A),
    onPrimary = Color(0xFF00391B),
    primaryContainer = Pine,
    onPrimaryContainer = Mint,
    secondary = Color(0xFF9FD5B2),
    secondaryContainer = Color(0xFF1F3A2A),
    onSecondaryContainer = Mint,
    background = Color(0xFF0E1511),
    onBackground = Color(0xFFDDE5DF),
    surface = Color(0xFF151D18),
    onSurface = Color(0xFFDDE5DF),
    surfaceVariant = Color(0xFF26332B),
    onSurfaceVariant = Color(0xFFB9C8BE),
    surfaceContainer = Color(0xFF1A241E),
    surfaceContainerHigh = Color(0xFF212C25),
    outline = Color(0xFF5C6E62),
    error = Color(0xFFFF8A80),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun AcciSafeTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

fun priorityColor(priority: String?): Color = when (priority) {
    "CRITICAL" -> Critical
    "HIGH" -> High
    "MEDIUM" -> Medium
    "LOW" -> Low
    else -> Color(0xFF90A4AE)
}

fun statusColor(status: String): Color = when (status) {
    "RESOLVED", "CLOSED", "COMPLETED" -> Resolved
    "CANCELLED", "REJECTED", "UNABLE_TO_RESPOND" -> Color(0xFF78909C)
    "AWAITING_REVIEW", "SUBMITTED" -> High
    "ASSISTANCE_REQUESTED" -> Critical
    else -> Emerald
}
