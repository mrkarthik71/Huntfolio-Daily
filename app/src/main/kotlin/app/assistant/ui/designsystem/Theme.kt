package app.assistant.ui.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A dark-first, Cred-inspired Material 3 theme: a near-black canvas, confident large type,
 * generous radii, and one restrained accent used as punctuation rather than decoration. Colours
 * are fixed (no dynamic colour) so contrast can be checked: text pairs reach at least 4.5:1 and
 * outlines 3:1 (Requirement 63.4, 63.5). The app is dark by identity; the light scheme is a
 * high-contrast fallback for users who force light (Requirement 10.9).
 */
object AssistantColors {
    // --- Cred-style dark canvas: ink-black base, two raised surface steps, soft outlines. ---
    private val Ink = Color(0xFF0B0B0F) // canvas
    private val Surface1 = Color(0xFF15151C) // cards
    private val Surface2 = Color(0xFF1E1E27) // raised / sheets
    private val OnInk = Color(0xFFF4F4F6) // primary text
    private val OnInkMuted = Color(0xFFA7A7B2) // secondary text
    private val HairlineDark = Color(0xFF2C2C36) // outlineVariant / dividers

    // Restrained signature accent: a desaturated indigo-violet, Cred's cool premium tone.
    private val Accent = Color(0xFFB7A8FF)
    private val AccentContainer = Color(0xFF2A2440)
    private val OnAccentContainer = Color(0xFFE6E0FF)

    val dark: ColorScheme = darkColorScheme(
        primary = Accent,
        onPrimary = Color(0xFF1B1430),
        primaryContainer = AccentContainer,
        onPrimaryContainer = OnAccentContainer,
        secondary = Color(0xFFC9C6D6),
        onSecondary = Color(0xFF1E1B2A),
        secondaryContainer = Surface2,
        onSecondaryContainer = OnInk,
        tertiary = Color(0xFF8FD6BA),
        onTertiary = Color(0xFF00382A),
        error = Color(0xFFFF9B94),
        onError = Color(0xFF3B0907),
        errorContainer = Color(0xFF4A1512),
        onErrorContainer = Color(0xFFFFD9D6),
        background = Ink,
        onBackground = OnInk,
        surface = Surface1,
        onSurface = OnInk,
        surfaceVariant = Surface2,
        onSurfaceVariant = OnInkMuted,
        surfaceContainerLowest = Ink,
        surfaceContainerLow = Surface1,
        surfaceContainer = Surface1,
        surfaceContainerHigh = Surface2,
        surfaceContainerHighest = Color(0xFF26262F),
        outline = Color(0xFF50505E),
        outlineVariant = HairlineDark,
    )

    // High-contrast light fallback (same accent, white canvas) for forced-light users.
    val light: ColorScheme = lightColorScheme(
        primary = Color(0xFF5B45C9),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFE7DFFF),
        onPrimaryContainer = Color(0xFF1B0C54),
        secondary = Color(0xFF4A4458),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEDE8F4),
        onSecondaryContainer = Color(0xFF1A1626),
        tertiary = Color(0xFF1F6B52),
        onTertiary = Color(0xFFFFFFFF),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        background = Color(0xFFFCFCFD),
        onBackground = Color(0xFF16161A),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF16161A),
        surfaceVariant = Color(0xFFF2F1F6),
        onSurfaceVariant = Color(0xFF5A5A66),
        outline = Color(0xFF8A8A96),
        outlineVariant = Color(0xFFD9D8E0),
    )
}

/**
 * Confident, tightly-tracked type. Large display/headline sizes carry the Cred feel; body stays
 * calm and readable. A shared line-height trim keeps big headings from floating.
 */
object AssistantType {
    private val trim = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    )

    val typography = Typography(
        displayLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.5).sp, lineHeightStyle = trim),
        displayMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.4).sp, lineHeightStyle = trim),
        headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
        headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 29.sp, letterSpacing = (-0.2).sp),
        titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp),
        titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
        bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
        labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
        labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.5.sp),
    )
}

/** Generous, Cred-style corner radii: cards and sheets are softly rounded, controls are pills. */
val AssistantShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun AssistantTheme(
    // Dark by identity: default to the dark scheme regardless of the system setting. A caller may
    // pass darkTheme=false to honour a user who forces light.
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) AssistantColors.dark else AssistantColors.light,
        typography = AssistantType.typography,
        shapes = AssistantShapes,
        content = content,
    )
}
