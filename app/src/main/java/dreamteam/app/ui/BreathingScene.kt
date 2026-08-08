package dreamteam.app.ui

import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dreamteam.app.BreathingSettings
import dreamteam.app.R
import kotlinx.coroutines.delay

/**
 * Redesign v2 ([DRE-211](/DRE/issues/DRE-211)) — the breathing screen (founder
 * review DRE-205 p.7: «для дыхания лучше предоставить приятные звуки + таймер
 * самого дыхания»). A dedicated scene with a visual pacer (box breathing
 * 4-4-4-4: inhale → hold → exhale → hold) + a per-phase countdown.
 *
 * Division of labour (DRE-211 vs [DRE-210](/DRE/issues/DRE-210)): the *session*
 * timer engine belongs to the Founding Engineer; this scene owns the *breathing*
 * pacer UI + its phase clock. A breathing pacer is a pure UI animation (a fixed
 * 4-phase cycle), so it lives here self-contained — no engine dependency. When a
 * richer engine lands it can drive the phase externally; the render is unchanged.
 * **Sound** is a graceful hook: when an audio asset is bundled, a soft tone marks
 * each phase transition; with none (the current build) the pacer is silent and
 * the visual carries it. No medical claim — a calm-focus pacer, framed as support.
 */
@Composable
internal fun BreathingScene(modifier: Modifier = Modifier, onBack: () -> Unit) {
    var running by remember { mutableStateOf(false) }
    // The active box-breathing phase (0=inhale, 1=hold-in, 2=exhale, 3=hold-out).
    var phase by remember { mutableIntStateOf(0) }
    // Seconds remaining in the current phase (4s per phase ⇒ 16s cycle).
    var remaining by remember { mutableIntStateOf(PHASE_SECONDS) }
    // Completed full cycles, surfaced as gentle feedback (not a goal/streak).
    var cycles by remember { mutableIntStateOf(0) }
    // DRE-235 — elapsed session time in seconds (counts while running).
    var elapsed by remember { mutableIntStateOf(0) }

    // --- DRE-235: SoundPool playback ------------------------------------------
    val context = LocalContext.current
    val soundOn = remember { mutableStateOf(BreathingSettings(context).isSoundEnabled()) }

    val soundPool = remember {
        SoundPool.Builder()
            .setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
    }

    val soundIds = remember { mutableStateOf<Map<BreathSound, Int>>(emptyMap()) }
    LaunchedEffect(Unit) {
        soundIds.value = mapOf(
            BreathSound.IN to soundPool.load(context, R.raw.breath_in, 1),
            BreathSound.HOLD to soundPool.load(context, R.raw.breath_hold, 1),
            BreathSound.OUT to soundPool.load(context, R.raw.breath_out, 1),
        )
    }

    DisposableEffect(soundPool) {
        onDispose { soundPool.release() }
    }

    /** DRE-235 — play the phase cue if sound is on and the asset is loaded. */
    fun cue(p: BreathPhase) {
        if (!soundOn.value) return
        val id = soundIds.value[p.sound] ?: return
        soundPool.play(id, 0.7f, 0.7f, 1, 0, 1f)
    }

    // The pacer clock: a 1s tick that counts the current phase down and advances.
    // LaunchedEffect keys on `running` so pausing cancels the tick cleanly.
    LaunchedEffect(running) {
        // DRE-235 — play cue immediately on Start / resume for instant feedback.
        if (running) cue(PHASES[phase])
        while (running) {
            delay(1_000)
            elapsed += 1
            val next = remaining - 1
            if (next > 0) {
                remaining = next
            } else {
                // Phase boundary: advance, reset the countdown, count a full cycle.
                val advanced = (phase + 1) % PHASES.size
                if (advanced == 0) cycles += 1
                phase = advanced
                remaining = PHASE_SECONDS
                cue(PHASES[advanced])
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screen, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(BreathingStrings.TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                // DRE-235 — sound toggle: contextual on the breathing screen.
                Text(BreathingStrings.SOUND, style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = soundOn.value,
                    onCheckedChange = { soundOn.value = it; BreathingSettings(context).setSoundEnabled(it) },
                )
            }
            TextButton(onClick = { running = false; onBack() }) { Text(BreathingStrings.BACK) }
        }

        Text(
            BreathingStrings.HINT,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BreathingPacer(phase = phase, running = running)

        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(
                "${PHASES[phase].label} · $remaining",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                BreathingStrings.cyclesLine(cycles),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // DRE-235 — elapsed session time (the breathing timer).
            Text(
                BreathingStrings.elapsedLine(elapsed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = { running = !running },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) BreathingStrings.PAUSE else BreathingStrings.START) }
    }
}

/**
 * The visual pacer: a circle that grows on inhale, holds, shrinks on exhale,
 * holds — synced to the active [phase]. Scale is animated so the motion reads as
 * breathing, not a step change.
 */
@Composable
private fun BreathingPacer(phase: Int, running: Boolean) {
    // Target scale per phase: inhale grows to 1.0, exhale shrinks to 0.6.
    val target = when (phase) {
        0 -> 1.0f   // inhale → full
        1 -> 1.0f   // hold at full
        2 -> 0.6f   // exhale → small
        else -> 0.6f // hold at small
    }
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = PHASE_SECONDS * 1000, easing = LinearEasing),
        label = "breathScale",
    )
    Box(
        modifier = Modifier
            .size(220.dp)
            .graphicsLayer { alpha = if (running) 1f else 0.5f },
        contentAlignment = Alignment.Center,
    ) {
        // ZEN v3 ([DRE-237](/DRE/issues/DRE-237), §8): a soft sage glow — the halo
        // fades to transparent (the glow), the orb carries a luminous radial
        // gradient (primaryContainer → primary) for depth instead of a flat fill.
        // Outer glow halo.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .scale(scale)
                .background(
                    Brush.radialGradient(
                        0.55f to MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                        1.0f to MaterialTheme.colorScheme.primary.copy(alpha = 0f),
                    ),
                    CircleShape,
                ),
        )
        // Inner orb — luminous sage radial gradient.
        Box(
            modifier = Modifier
                .size(120.dp)
                .scale(scale)
                .background(
                    Brush.radialGradient(
                        0f to MaterialTheme.colorScheme.primaryContainer,
                        0.7f to MaterialTheme.colorScheme.primary,
                        1.0f to MaterialTheme.colorScheme.primary,
                    ),
                    CircleShape,
                ),
        )
    }
}

/** Seconds each box-breathing phase lasts (4-4-4-4 ⇒ 16s cycle). */
private const val PHASE_SECONDS = 4

/** DRE-235 — sound cue kind, decoupled from Android R for JVM testability. */
internal enum class BreathSound { IN, HOLD, OUT }

/** The four box-breathing phases, in cycle order, each with its RU cue label + sound. */
internal enum class BreathPhase(val label: String, val sound: BreathSound) {
    INHALE("Вдох", BreathSound.IN),
    HOLD_IN("Задержка", BreathSound.HOLD),
    EXHALE("Выдох", BreathSound.OUT),
    HOLD_OUT("Задержка", BreathSound.HOLD),
}

/** Cycle-ordered phases; internal so the phase→sound mapping is JVM-testable. */
internal val PHASES = BreathPhase.entries

/** Authored copy for the breathing scene. Support framing — calm focus, no claim. */
internal object BreathingStrings {
    const val TITLE = "Дыхание"
    const val BACK = "Назад"
    const val HINT = "Спокойный ритм 4–4–4–4. Дышите мягко, без усилия. Прекратите при головокружении."
    const val START = "Начать"
    const val PAUSE = "Пауза"
    fun cyclesLine(n: Int) = "Циклов: $n"
    /** DRE-235 — elapsed session time, formatted mm:ss. */
    fun elapsedLine(s: Int) = "%02d:%02d".format(s / 60, s % 60)
    /** DRE-235 — sound toggle label. */
    const val SOUND = "Звук"
    val all: List<String> = listOf(TITLE, BACK, HINT, START, PAUSE, cyclesLine(0), elapsedLine(0), SOUND)
}
