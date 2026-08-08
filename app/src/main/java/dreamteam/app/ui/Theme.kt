package dreamteam.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
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
    // v3: warm clay — a gentle tertiary accent for breathing/secondary highlights.
    tertiary = Color(0xFFB58263),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEEDBC8),
    onTertiaryContainer = Color(0xFF3E2415),
    background = Color(0xFFFAF7F2),
    onBackground = Color(0xFF2E2A26),
    // v3: soften the stark #FFFFFF to a warm paper-white so cards read with
    // presence against the washi canvas instead of disappearing into it.
    surface = Color(0xFFFFFDF9),
    onSurface = Color(0xFF2E2A26),
    surfaceVariant = Color(0xFFF1EBE2),
    onSurfaceVariant = Color(0xFF6B645B),
    surfaceContainer = Color(0xFFF6F1E9),
    // v3: elevated surfaces tint toward sage (calm depth, not grey drop-shadow).
    surfaceTint = Color(0xFF5E8B7E),
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
    // v3: warm clay tertiary (dark variant).
    tertiary = Color(0xFFD0A684),
    onTertiary = Color(0xFF3E2415),
    tertiaryContainer = Color(0xFF634431),
    onTertiaryContainer = Color(0xFFEEDBC8),
    background = Color(0xFF1C1B1A),
    onBackground = Color(0xFFEDE8E0),
    surface = Color(0xFF262422),
    onSurface = Color(0xFFEDE8E0),
    surfaceVariant = Color(0xFF322F2C),
    onSurfaceVariant = Color(0xFFB5ADA2),
    surfaceContainer = Color(0xFF2A2826),
    surfaceTint = Color(0xFF8FAB9F),
    outline = Color(0xFF423E3A),
    outlineVariant = Color(0xFF332F2C),
)

/**
 * ZEN v3 named palette ([DRE-241](/DRE/issues/DRE-241)) — the semantic intent
 * behind the M3 [lightColorScheme]/[darkColorScheme] tokens above. Designers and
 * Epic-B implementers reason in these names; the Compose tree consumes the M3
 * roles. Keep the hex here in sync with the schemes — they are two views of one
 * system. None of these is a new dependency (pure [Color] constants).
 *
 * Roles:
 *  - [Ink]      all body/heading text (warm charcoal, never pure black)
 *  - [Paper]    the canvas (warm washi off-white)
 *  - [Matcha]   primary accent — the calm sage-teal hero
 *  - [Sand]     secondary — warm gold for gentle emphasis
 *  - [Clay]     tertiary — soft terracotta for breathing/secondary highlights
 *  - [Stone]    cool neutral for muted meta text
 *  - [Mist]     hairline dividers / whisper-faint fills
 */
internal object ZenPalette {
    val Ink = Color(0xFF2E2A26)
    val Paper = Color(0xFFFAF7F2)
    val Matcha = Color(0xFF5E8B7E)
    val Sand = Color(0xFFC9A66B)
    val Clay = Color(0xFFB58263)
    val Stone = Color(0xFF6B645B)
    val Mist = Color(0xFFE8E2D6)
}

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
    /** 32dp — the calm break between major top-level sections (ZEN v3: generous air = calm). */
    val section = 32.dp

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

/**
 * ZEN v3 shape system ([DRE-241](/DRE/issues/DRE-241)) — the corner vocabulary
 * for every surface. [AppCardShape] stays the canonical card shape existing call
 * sites already use; [ZenShape] is the full family Epic-B adopts for the rest.
 * Soft, generous radii read as calm and tactile — never sharp.
 */
internal val AppCardShape = RoundedCornerShape(24.dp)

internal object ZenShape {
    /** 24dp — the default card / container. (== [AppCardShape]) */
    val card = RoundedCornerShape(24.dp)
    /** 28dp — larger scenes: breathing, full-screen sheets, dialogs. */
    val cardLarge = RoundedCornerShape(28.dp)
    /** 16dp — inputs, text fields, dense inline surfaces. */
    val field = RoundedCornerShape(16.dp)
    /** fully rounded — chips, pills, and primary action buttons. */
    val pill = RoundedCornerShape(50)
}

// ── Elevation ──────────────────────────────────────────────────────────────

/**
 * ZEN v3 elevation ([DRE-241](/DRE/issues/DRE-241)) — calm is conveyed by
 * **tonal layering** (warmer/lighter surface tiers via `surfaceContainer` /
 * `surfaceVariant` + `surfaceTint`), not harsh drop-shadows. The dp values here
 * are the shadow elevations Material falls back to when a surface is lifted;
 * keep them low so depth stays whisper-soft. Epic-B prefers flat layered cards.
 */
internal object ZenElevation {
    /** Flat — the resting card. Depth comes from its surface tier, not a shadow. */
    val resting = 0.dp
    /** Raised — a card lifted one notch (subtle). */
    val raised = 1.dp
    /** Floating — FABs, sticky headers. */
    val floating = 3.dp
    /** Overlay — dialogs / bottom sheets over content. */
    val overlay = 6.dp
}

// ── Motion ──────────────────────────────────────────────────────────────────

/**
 * ZEN v3 motion system ([DRE-241](/DRE/issues/DRE-241)). Calm, slow, deliberate:
 * nothing snaps. The two legacy specs ([calm], [breath]) are unchanged so
 * existing call sites keep compiling; the added durations / easings / specs are
 * the vocabulary Epic-B uses for screen transitions, card reveals, and the
 * breathing pacer. No new dependency — all built on Compose animation core.
 */
internal object Motion {
    // ── Durations (ms) ──
    /** 150ms — micro feedback: ripple, toggling a chip, a focus ring. */
    const val microMs = 150
    /** 250ms — small: a button press scale, a row expanding. */
    const val smallMs = 250
    /** 400ms — medium: a card content cross-fade, a sheet sliding in. */
    const val mediumMs = 400
    /** 600ms — large: a card expanding, a screen entering. */
    const val largeMs = 600

    // ── Easings ──
    /** Emphasized — the Material default; the natural "settle" for entrances. */
    val Emphasized = FastOutSlowInEasing
    /** Emphasized decelerate — things entering the screen (fast in, slow settle). */
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Emphasized accelerate — things leaving (slow start, fast out). */
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    /** Calm — a gentler, longer-arc ease for ambient motion (breathing, ambient loops). */
    val Calm = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    // ── Specs ──
    /** Card expand / screen enter. (legacy — unchanged) */
    val calm = TweenSpec<Float>(durationMillis = largeMs, easing = Emphasized)
    /** A gentle fade/scale for content swaps. */
    val gentle = TweenSpec<Float>(durationMillis = mediumMs, easing = Calm)
    /** Quick tap feedback. */
    val quick = TweenSpec<Float>(durationMillis = smallMs, easing = Emphasized)
    /** Breathing pacer cycle phase (legacy — unchanged). */
    val breath = TweenSpec<Float>(durationMillis = 4000, easing = LinearEasing)
    /** Soft spring for interactive elements (buttons, the breathing orb). */
    val softSpring: SpringSpec<Float> = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
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
