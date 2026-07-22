package dreamteam.domain.adaptation

import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.symptom.Symptom
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.LocalDate

/**
 * What the plan generator may do to the baseline in response to the user's own
 * logged data (progress + symptoms). M3-A ([DRE-49](/DRE/issues/DRE-49)): close
 * the loop so the plan reacts to logged inputs — **still deterministic, still
 * behind [dreamteam.domain.safety.SafetyGuardedGateway.surface], no LLM**.
 *
 * ## De-load only (a type-system invariant)
 *
 * The sealed hierarchy has exactly two members: [None] (serve the baseline) and
 * [DeLoad] (reduce training volume within safe bounds). There is **no "load
 * more" / "intensify" variant**, by design: a future engineer cannot express
 * "increase load when a symptom is flagged" without first editing this type,
 * which is the review point the de-load-only invariant ([ADR 0004](../../../../../docs/adr/0004-adaptation-de-load-only.md))
 * wants to force. This is the hard rule from [DRE-48](/DRE/issues/DRE-48):
 * "adaptation only lowers risk (de-load/hold), never bypasses the safety gate".
 *
 * The signal is **not** a recommendation and carries no evidence refs: it is an
 * internal volume modifier, never surfaced as guidance, so it does not flow
 * through the evidence-allowlist gate (correctly — its thresholds reference the
 * PoC decision rules, not a study citation).
 */
@Serializable
sealed interface AdaptationSignal {

    /** Serve the baseline plan unchanged. */
    @Serializable
    data object None : AdaptationSignal

    /**
     * Reduce training volume. [volumeScale] is a multiplicative factor on each
     * week's working-set count, in `[[SCALE_FLOOR], 1.0]` — always `< 1.0` here
     * (a no-op reduction would be [None]). The generator clamps the result to
     * `[DELOAD_SETS_FLOOR, baseline]` per week, so load can only stay equal or
     * fall, never rise. [reason] is a plain-language, support-framed note (no
     * diagnosis/claim) carried into the plan for explainability (M3-C will
     * surface it; M3-A only stores it).
     */
    @Serializable
    data class DeLoad(
        val trigger: DeLoadTrigger,
        val volumeScale: Double,
        val reason: String,
    ) : AdaptationSignal

    companion object {
        /** Hardest allowed cut. Generator also enforces a per-week integer floor. */
        const val SCALE_FLOOR: Double = 0.5

        /** Moderate de-load: ~25% volume cut (Decision_Rules YELLOW "reduce sets 25–50%"). */
        const val SCALE_MODERATE: Double = 0.75

        /** Both triggers fire: deeper (~40%) cut, still within the 25–50% YELLOW band. */
        const val SCALE_STRONG: Double = 0.6
    }
}

/**
 * Why a [AdaptationSignal.DeLoad] was emitted. Typed (not a free string) so a
 * test/audit can assert on the cause. Both members describe a *recovery stress*
 * signal — never a diagnosis.
 */
@Serializable
sealed interface DeLoadTrigger {
    /** A symptom string appeared in the latest entry that was absent from prior entries. */
    @Serializable
    data object SymptomEscalation : DeLoadTrigger

    /** Weekly weight-loss rate exceeded the rapid-loss threshold (Decision_Rules). */
    @Serializable
    data object RapidWeightLoss : DeLoadTrigger
}

/**
 * Derive the [AdaptationSignal] from logged progress + symptoms. **Pure**:
 * same inputs → same signal, every time; no I/O, no clock, no LLM. Consumed by
 * [dreamteam.domain.training.DeterministicPlanGenerator].
 *
 * Triggers (both conservative — need a *change*, never a single point):
 *  - **Symptom escalation** — a symptom string in the most recent [Symptom]
 *    entry that was not present in any earlier entry (a new symptom). Needs
 *    ≥2 symptom entries to detect "new" (one entry cannot establish a change).
 *    Maps to Decision_Rules training-readiness YELLOW ("pain/tension clearly
 *    elevated"). RED-flag symptoms are a separate, harder gate handled upstream
 *    by [dreamteam.domain.safety.SafetyGate] (they block generation entirely);
 *    this function never sees them as "just" an adaptation input.
 *  - **Rapid weight loss** — endpoint weekly rate `r < [RAPID_LOSS_PER_WEEK]`
 *    over a span of ≥1 week. `r` mirrors Decision_Rules' `r` (weekly fractional
 *    weight change); `r < -0.0075` is the spec's "recovery/performance
 *    deteriorates" branch. Computed from raw points (not 7-day means) in M3-A —
 *    noisier than the spec; M3-B will add 7-day means. Conservative direction
 *    only: weight *gain* never triggers a de-load here.
 *
 * Either trigger → [AdaptationSignal.DeLoad]; both → a stronger cut. Neither →
 * [AdaptationSignal.None]. The result is always de-load or none — never an
 * increase (see [AdaptationSignal]).
 */
fun deriveAdaptationSignal(
    progress: List<ProgressEntry>,
    symptoms: List<Symptom>,
): AdaptationSignal {
    val symptomEscalation = detectSymptomEscalation(symptoms)
    val rapidLoss = detectRapidWeightLoss(progress)
    return when {
        symptomEscalation && rapidLoss -> AdaptationSignal.DeLoad(
            trigger = DeLoadTrigger.SymptomEscalation, // primary; both fired
            volumeScale = AdaptationSignal.SCALE_STRONG,
            reason = "снижение объёма: новый симптом + быстрое снижение веса (тренд ≥1 нед)",
        )
        symptomEscalation -> AdaptationSignal.DeLoad(
            trigger = DeLoadTrigger.SymptomEscalation,
            volumeScale = AdaptationSignal.SCALE_MODERATE,
            reason = "снижение объёма: появился новый/усиливающийся симптом",
        )
        rapidLoss -> AdaptationSignal.DeLoad(
            trigger = DeLoadTrigger.RapidWeightLoss,
            volumeScale = AdaptationSignal.SCALE_MODERATE,
            reason = "снижение объёма: быстрое снижение веса (тренд ≥1 нед)",
        )
        else -> AdaptationSignal.None
    }
}

/** Decision_Rules (specs/Decision_Rules.md): `r < -0.0075` ⇒ recovery deteriorates. */
private const val RAPID_LOSS_PER_WEEK = -0.0075

/** Need ≥1 week of span to call two points a weekly rate (M3 = weekly loop). */
private const val MIN_TREND_WEEKS = 1.0

private fun detectSymptomEscalation(symptoms: List<Symptom>): Boolean {
    if (symptoms.size < 2) return false
    val sorted = symptoms.sortedBy { it.recordedOn }
    val latest = sorted.last()
    val priorUnion = sorted.dropLast(1).flatMap { it.currentSymptoms }.toSet()
    return latest.currentSymptoms.any { it !in priorUnion }
}

private fun detectRapidWeightLoss(progress: List<ProgressEntry>): Boolean {
    if (progress.size < 2) return false
    val sorted = progress.sortedBy { it.recordedOn }
    val first = sorted.first()
    val last = sorted.last()
    if (first.weightKg <= 0.0) return false
    val weeks = try {
        Duration.between(
            LocalDate.parse(first.recordedOn).atStartOfDay(),
            LocalDate.parse(last.recordedOn).atStartOfDay(),
        ).toDays() / 7.0
    } catch (e: Exception) {
        return false // unparseable date → cannot establish a trend → no signal
    }
    if (weeks < MIN_TREND_WEEKS) return false
    val weeklyRate = (last.weightKg - first.weightKg) / first.weightKg / weeks
    return weeklyRate < RAPID_LOSS_PER_WEEK
}
