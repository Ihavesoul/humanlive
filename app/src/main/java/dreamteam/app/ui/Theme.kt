package dreamteam.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dreamteam.app.R

/**
 * ZEN design system ([DRE-205](/DRE/issues/DRE-205)): the app's product theme,
 * replacing the prior Nike-Fitness dark/green identity with a calm, warm,
 * minimalist palette — sage-teal accent on warm off-white (light) or
 * warm-dark (dark) canvas. Humanist Mulish type for readable, non-"cheap" text.
 *
 * Behaviour is unchanged — every existing `Text`/`Card`/`Button` picks up its
 * colour from `MaterialTheme.colorScheme` and type from
 * `MaterialTheme.typography`, so swapping the tokens restyles the whole tree
 * without touching composition.
 *
 * No new Gradle dependency: the palette is pure Material 3 `ColorScheme`,
 * the type scale is `Typography`, and the font is a bundled TTF resource.
 */

// ── Palette ────────────────────────────────────────────────────────────────

/** Light scheme (default — warm off-white paper, sage-teal accent). */
private val LightColors = lightColorScheme(
    primary = Color(0xFF5E8B7E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E6DF),
    onPrimaryContainer = Color(0xFF1F3D34),
    secondary = Color(0xFFC9A66B),
    onSecondary = Color(0xFF3A2E16),
    background = Color(0xFFFAF7F2),
    onBackground = Color(0xFF2E2A26),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF2E2A26),
    surfaceVariant = Color(0xFFF1EBE2),
    onSurfaceVariant = Color(0xFF6B645B),
    surfaceContainer = Color(0xFFF6F1E9),
    outline = Color(0xFFE0D9CE),
    outlineVariant = Color(0xFFECE6DC),
)

/** Dark scheme (calm warm-dark — NOT near-black Nike). */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FAB9F),
    onPrimary = Color(0xFF12231C),
    primaryContainer = Color(0xFF345047),
    onPrimaryContainer = Color(0xFFD7E6DF),
    secondary = Color(0xFFD9BC89),
    onSecondary = Color(0xFF1F1810),
    background = Color(0xFF1C1B1A),
    onBackground = Color(0xFFEDE8E0),
    surface = Color(0xFF262422),
    onSurface = Color(0xFFEDE8E0),
    surfaceVariant = Color(0xFF322F2C),
    onSurfaceVariant = Color(0xFFB5ADA2),
    surfaceContainer = Color(0xFF2A2826),
    outline = Color(0xFF423E3A),
    outlineVariant = Color(0xFF332F2C),
)

// ── Typography ────────────────────────────────────────────────────────────

/**
 * Mulish — bundled humanist sans (fixes "cheap text" board feedback).
 * Variable font: single TTF covers Normal → Bold via the `wght` axis.
 * Each [Font] entry tells Compose which weight the file can satisfy.
 */
private val Mulish = FontFamily(
    Font(R.font.mulish_variable, FontWeight.Normal),
    Font(R.font.mulish_variable, FontWeight.Medium),
    Font(R.font.mulish_variable, FontWeight.SemiBold),
    Font(R.font.mulish_variable, FontWeight.Bold),
)

/**
 * ZEN type scale — Mulish family, raised floors + generous line-height for
 * readability on phones (especially RU text with long exercise descriptions).
 */
private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = Mulish,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Mulish,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Mulish,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Mulish,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Mulish,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Mulish,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Mulish,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Mulish,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Mulish,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
    ),
)

// ── Spacing ───────────────────────────────────────────────────────────────

/**
 * The spacing scale — the single source of truth for every gap and edge in the
 * app (redesign v2, [DRE-211](/DRE/issues/DRE-211); supersedes the M10 3-token
 * set from [DRE-189](/DRE/issues/DRE-189)). 4dp-base scale (reference: Material 3
 * spacing guidance) consumed everywhere via tokens, so the whole app shares ONE
 * visual rhythm.
 *
 * The three legacy names ([card] / [itemGap] / [tightGap]) are kept as the
 * canonical members so existing call sites keep compiling; new code uses the
 * named scale.
 */
internal object Spacing {
    /** 4dp — the base unit. Hairline gaps, divider insets, tight grouping. */
    val xs = 4.dp
    /** 8dp — chips, adjacent controls, intra-row separation. (legacy: itemGap) */
    val sm = 8.dp
    /** 12dp — between a label and the block it titles, mid-density list rows. */
    val md = 12.dp
    /** 16dp — inside a Card, the edge-to-content air. (legacy: card) */
    val lg = 16.dp
    /** 20dp — between top-level sections on a screen. */
    val xl = 20.dp
    /** 24dp — the largest break: screen header → first section, scene margins. */
    val xxl = 24.dp

    /** Screen edge padding — the air between the window and the content column. */
    val screen = 16.dp
    /** Inside a Card — the air between a card edge and its content. (legacy) */
    val card = lg
    /** Between items inside one block (e.g. meal rows inside the nutrition card). (legacy) */
    val itemGap = sm
    /** A grouped label sitting just above its own card (kept tight on purpose). (legacy) */
    val tightGap = xs

    /** Touch-target floor (Material guidance): a row of action buttons is never shorter. */
    val touchTarget = 48.dp
    /** The 16:9 media slot height reserved on an exercise card for its image. */
    val exerciseMediaHeight = 180.dp
}

// ── Shape ──────────────────────────────────────────────────────────────────

/**
 * Softened card corners — calmer, more "spa" than the prior 20dp.
 */
internal val AppCardShape = RoundedCornerShape(24.dp)

// ── Motion ──────────────────────────────────────────────────────────────────

/** Calm easing/duration tokens for card expand, breathing animation, etc. */
internal object Motion {
    val calm = TweenSpec<Float>(durationMillis = 600, easing = FastOutSlowInEasing)
    val breath = TweenSpec<Float>(durationMillis = 4000, easing = LinearEasing)
}

// ── Theme wrapper ──────────────────────────────────────────────────────────

/**
 * The app theme wrapper. Follows the system setting by default (ZEN reads best
 * in warm light); pass `forceDark = true` to force dark mode.
 */
@Composable
fun DreamTeamTheme(
    forceDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (forceDark || isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
