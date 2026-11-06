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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.assistant.R

/**
 * "Instrument panel" (design direction B, chosen by the User): a graphite console lit from above.
 * Raised panels catch a highlight on their top edge and cast a soft shadow; readouts sit in
 * recessed wells; one signal orange marks what is live and the main action. Text pairs reach at
 * least 4.5:1 and outlines 3:1 (Requirement 63.4, 63.5). Dark by identity; the light scheme is a
 * high-contrast fallback for users who force light (Requirement 10.9).
 */
object AssistantColors {
    /** Console ground (top of the screen glows a little lighter: see [Instrument.groundGlow]). */
    val Ground = Color(0xFF0E0F10)
    val GroundGlow = Color(0xFF1C1D20)

    /** Raised panel: top and bottom of its vertical gradient. */
    val PanelTop = Color(0xFF202226)
    val PanelBottom = Color(0xFF141517)

    /** Recessed well for readouts and inputs. */
    val Well = Color(0xFF131416)
    val WellDeep = Color(0xFF0D0E0F)

    val Ink = Color(0xFFEDEBE6)
    val InkSecondary = Color(0xFFB9B6AF)
    val InkMuted = Color(0xFF8A8780)
    val Hairline = Color(0xFF2A2C30)

    /** Signal orange: live state, progress, the primary action. */
    val Signal = Color(0xFFFF8A3D)
    val SignalHot = Color(0xFFFFA061)
    val OnSignal = Color(0xFF1A0E05)

    val Good = Color(0xFF7FD8A6)
    val Info = Color(0xFF8FC1FF)
    val Neutral = Color(0xFFD8D5CD)
    val Alert = Color(0xFFFF7A6B)

    val dark: ColorScheme = darkColorScheme(
        primary = Signal,
        onPrimary = OnSignal,
        primaryContainer = Color(0xFF2B1A0E),
        onPrimaryContainer = Color(0xFFFFD9BF),
        secondary = InkSecondary,
        onSecondary = Ground,
        secondaryContainer = PanelTop,
        onSecondaryContainer = Ink,
        tertiary = Good,
        onTertiary = Color(0xFF00301A),
        error = Alert,
        onError = Color(0xFF3B0907),
        errorContainer = Color(0xFF3D1512),
        onErrorContainer = Color(0xFFFFD9D6),
        background = Ground,
        onBackground = Ink,
        surface = Color(0xFF17181B),
        onSurface = Ink,
        surfaceVariant = PanelTop,
        onSurfaceVariant = InkMuted,
        surfaceContainerLowest = WellDeep,
        surfaceContainerLow = Well,
        surfaceContainer = Color(0xFF17181B),
        surfaceContainerHigh = PanelTop,
        surfaceContainerHighest = Color(0xFF282A2E),
        outline = Color(0xFF55575C),
        outlineVariant = Hairline,
    )

    val light: ColorScheme = lightColorScheme(
        primary = Color(0xFFA34A0B),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFFFE1CC),
        onPrimaryContainer = Color(0xFF341300),
        secondary = Color(0xFF4F4D48),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEDEAE3),
        onSecondaryContainer = Color(0xFF1C1B18),
        tertiary = Color(0xFF1F6B47),
        onTertiary = Color(0xFFFFFFFF),
        error = Color(0xFFB3261E),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        background = Color(0xFFF6F5F2),
        onBackground = Color(0xFF161616),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF161616),
        surfaceVariant = Color(0xFFEDEAE3),
        onSurfaceVariant = Color(0xFF55534E),
        outline = Color(0xFF85837D),
        outlineVariant = Color(0xFFD8D5CD),
    )
}

/**
 * Space Grotesk for words, JetBrains Mono for every number, time and caption (bundled, SIL OFL;
 * licences in assets/licenses). Numbers in mono line up like readouts on a panel.
 */
object AssistantType {
    val Grotesk = FontFamily(
        Font(R.font.space_grotesk_regular, FontWeight.Normal),
        Font(R.font.space_grotesk_medium, FontWeight.Medium),
        Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
        Font(R.font.space_grotesk_bold, FontWeight.Bold),
    )

    val Mono = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
        Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    )

    /** A large mono readout (amounts, timers, the dial). */
    val Readout = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-1).sp)

    /** The small uppercase mono caption ("LIVE · DEEP WORK", "SPENT TODAY"). */
    val Caption = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.1.sp)

    val typography = Typography(
        displayLarge = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 48.sp, lineHeight = 52.sp, letterSpacing = (-2).sp),
        displayMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-1).sp),
        displaySmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
        headlineLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp),
        headlineMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp),
        headlineSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.3).sp),
        titleLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 23.sp, letterSpacing = (-0.2).sp),
        titleMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 21.sp),
        titleSmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp),
        bodyLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
        labelMedium = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
        labelSmall = Caption,
    )
}

/** Instrument radii: panels 24–28, wells 20, keys 16, pills full. */
val AssistantShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
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
    ) { ProvideMotion(content) }
}
