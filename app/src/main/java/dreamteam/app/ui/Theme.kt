package dreamteam.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * M8-D ([DRE-90](/DRE/issues/DRE-90)): the app's product theme. The M2–M7
 * screens used the bare default `MaterialTheme`, which read as an "MD-viewer"
 * (wall of unstyled text on a flat grey background). This theme gives the app a
 * deliberate, media-forward fitness-app identity (reference: Nike Fitness):
 * dark, near-black canvas with a single green accent — the SAME green as the
 * launcher background ([dreamteam.app] `ic_launcher_background` `#0B6E4F`) — so
 * brand colour is consistent end to end. **No new dependency**: pure Material 3
 * `ColorScheme` + `Typography`, both already on the Compose classpath.
 *
 * Behaviour is unchanged — every existing `Text`/`Card`/`Button` picks up its
 * colour from `MaterialTheme.colorScheme` via `LocalContentColor`, so swapping
 * the default scheme for this one restyles the whole tree without touching the
 * composition. The safety gate, the deterministic plan, the evidence links and
 * the support-framed copy are all untouched (the deliverable's invariants).
 */
private val BrandGreen = Color(0xFF0B6E4F)
private val BrandGreenMuted = Color(0xFF1F8F6C)

/** Dark-first canvas (the fitness-app reference is dark); surfaces step up from it. */
private val DarkColors = darkColorScheme(
    primary = BrandGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0C5A41),
    onPrimaryContainer = Color(0xFFB8EEDB),
    secondary = BrandGreenMuted,
    onSecondary = Color.White,
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFECECEE),
    surface = Color(0xFF161619),
    onSurface = Color(0xFFECECEE),
    surfaceVariant = Color(0xFF222228),
    onSurfaceVariant = Color(0xFFA6A6AE),
    surfaceContainer = Color(0xFF1B1B1F),
    outline = Color(0xFF38383F),
    outlineVariant = Color(0xFF2A2A30),
)

/** Light fallback for high-ambient-light / accessibility (same brand accent). */
private val LightColors = lightColorScheme(
    primary = BrandGreen,
    onPrimary = Color.White,
    secondary = BrandGreenMuted,
    background = Color(0xFFF7F8F8),
    onBackground = Color(0xFF16181A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16181A),
    surfaceVariant = Color(0xFFE9EBEC),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceContainer = Color(0xFFF1F3F3),
    outline = Color(0xFFC3C7CA),
    outlineVariant = Color(0xFFDADDE0),
)

/**
 * Clean, deliberate type scale tuned for a phone (M10 [DRE-189](/DRE/issues/DRE-189)).
 * Keeps the default Material 3 family (no custom font asset — YAGNI: a font
 * resource is a deployable nobody asked for); only weights, sizes and tracking
 * are tuned so headings read as headings and body stays comfortable. The M9
 * density pass pushed body copy to 12.5sp, which read as micro-text on a real
 * device — the floors here are raised so the smallest body line is comfortable
 * on a 360dp phone, and line heights leave room for the long RU labels to wrap
 * cleanly instead of colliding (maintainer feedback: «микро-текст», «шрифты
 * ужасны»). Hierarchy is unchanged; every existing Text picks the same style.
 */
private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp),
)

/**
 * M10 ([DRE-189](/DRE/issues/DRE-189)): one small set of spacing tokens so the
 * app has a consistent visual rhythm instead of scattered literal `12.dp` /
 * `2.dp` / `4.dp` paddings (maintainer: «отступов нет нормальных», «приложение
 * для роботов»). Pure values — no new dependency. The M9 density pass crammed
 * cards at 12dp padding with 2–4dp gaps, which read as a wall; these reverse
 * that only where it harmed readability (the gate/plan/logic is untouched —
 * layout/spacing only). Cards breathe at [cardPadding]; sections separate at
 * [sectionGap]; tight label→content pairs stay grouped at [tightGap].
 */
internal object Spacing {
    /** Edge padding for a full-screen scroll surface. */
    val screen = 16.dp
    /** Inside a Card — the air between a card edge and its content. */
    val card = 16.dp
    /** Between sibling sections / cards in a scroll (the rhythm between blocks). */
    val sectionGap = 12.dp
    /** Between items inside one block (e.g. meal rows inside the nutrition card). */
    val itemGap = 8.dp
    /** A grouped label sitting just above its own card (kept tight on purpose). */
    val tightGap = 4.dp
}

/**
 * The app theme wrapper. Dark by default (the product reference is dark); pass
 * `forceDark = false` to follow the system setting. Light is kept for
 * accessibility, not as the default look.
 */
@Composable
fun DreamTeamTheme(
    forceDark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (forceDark || isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
}
