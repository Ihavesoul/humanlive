package dreamteam.domain.coach

import dreamteam.domain.ExerciseId
import dreamteam.domain.UserId
import dreamteam.domain.adaptation.AdaptationSignal
import dreamteam.domain.adaptation.deriveAdaptationSignal
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.safety.SafetyGate
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.MedicalSafety
import dreamteam.domain.safety.SafetyEvaluation
import dreamteam.domain.safety.provisionedSafetyGateway
import dreamteam.domain.symptom.Symptom
import dreamteam.domain.training.BaselineProgram
import dreamteam.domain.training.DeterministicPlanGenerator
import dreamteam.domain.training.GeneratedPlan
import dreamteam.domain.training.TrainingPlan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * M8-C ([DRE-89](/DRE/issues/DRE-89)): the AI coach. Owns the **full context**
 * (profile, current plan, past workout notes, symptoms) and turns it into a
 * **phone-readable** result the app renders directly — no prompt-engineering on
 * the phone, no free-form "reflections" in the UI.
 *
 * ## Safety model (the non-negotiables, ADR 0001 / Safety_Threat_Model.md)
 *
 *  - **#1 Safety gate = code, not model.** [report] runs the pre-LLM
 *    [SafetyGate] (red-flag gate + side-specific lock) and the adapted plan is
 *    produced **exclusively** by [DeterministicPlanGenerator] through
 *    [SafetyGuardedGateway.surface]. The LLM never authors the plan: it only
 *    annotates it ([CoachReport.summaryRu] + [CoachReport.corrections]). A red
 *    flag blocks before any provider is called; the gate's verdict cannot be
 *    overridden, dismissed, or bypassed by coach text.
 *  - **#2 Evidence by server allowlist.** [validateProviderText] rejects any
 *    provider text carrying a fabricated `DOI`/`PMID`/`URL` and any
 *    `exercise_id` outside [BaselineProgram.exerciseIds].
 *  - **#4 Deterministic fallback.** The fallback ([fallbackReport] /
 *    [fallbackExplain]) is built FIRST, before any provider call. A provider
 *    that is absent, errors, times out, or returns invalid output leaves the
 *    fallback standing — the user is never stranded without a result.
 *  - **#5 Single LLM seam.** This type is pure domain; the only LLM path is the
 *    [CoachProvider] port. An operator wires it on the **server** ([dreamteam
 *    server coach]); DRE-175 additionally lets the app inject a user-creds
 *    [CoachProvider] (their own URL+token) so "Спросить у AI" works before the
 *    operator key ([DRE-130](/DRE/issues/DRE-130)) is provisioned. Either way the
 *    provider result is enrichment only — the gate, the adapted plan, and
 *    [validateProviderText] still own safety; the client never calls a provider
 *    that bypasses them.
 *
 * The provider result is *enrichment only*: it may replace the fallback's text
 * (`summary_ru`, `corrections`) when it passes [validateProviderText], but the
 * [adaptedPlan], [originalPlanId], and [source] are controlled by this type.
 *
 * Pure + synchronous so a JVM test pins every guarantee without a device or
 * coroutine runtime: the provider is a plain function returning `String?`
 * (null ⇒ treat as unavailable/failed → fallback stands).
 *
 * This type implements the safety *plumbing*; it does not author clinical
 * content. Note-pain detection is a conservative *de-load-only* mechanism; any
 * clinical trigger/contraindication is a Safety Reviewer decision wired into the
 * active [dreamteam.domain.safety.SafetyRule]s the gateway evaluates.
 */
class Coach(
    /** Produces a provisioned gateway for (medical, safetyEval) — the SAME wiring the plan routes use. */
    private val gatewayFor: (MedicalSafety, SafetyEvaluation) -> SafetyGuardedGateway = ::provisionedSafetyGateway,
    /** Resolves an exercise id to its user-facing name (BaselineProgram mirror). */
    private val exerciseName: (ExerciseId) -> String = { id -> BaselineProgram.exercises[id]?.name ?: id },
    /** The optional LLM provider. null ⇒ the deterministic fallback always stands. */
    private val provider: CoachProvider? = null,
) {

    /**
     * "Сообщить коучу" CTA — the end-of-workout report. Reads the session's
     * per-exercise notes + symptoms, derives the note-pain signal, builds the
     * gated adapted next plan, then (optionally) enriches the text via the
     * provider. [originalPlanId] is preserved so the UI can offer
     * "оригинал vs адаптация" with the original retained.
     *
     * A red-flag profile → [CoachReport.Blocked] (medical-safety gate; no
     * provider call). Otherwise the adapted plan is gate-Ok; if a future rule
     * tightening ever blocked a baseline movement, [report] degrades to
     * [CoachReport.Unavailable] (keep the original plan) rather than crash or
     * leak a hole — the red-flag gate still passed, so it is not a medical block.
     */
    fun report(
        userId: UserId,
        createdAt: String,
        medical: MedicalSafety,
        originalPlanId: String,
        notes: List<CoachNote>,
        symptoms: List<Symptom> = emptyList(),
        progress: List<ProgressEntry> = emptyList(),
        adaptedPlanId: String = "${userId}@${createdAt}",
    ): CoachReport {
        // #1: pre-LLM red-flag gate. Binding; the provider is never called when blocked.
        // DRE-100: free-text note language is screened first and merged into the gate
        // input, so a note-derived red flag blocks identically to a structured one.
        val safety = SafetyGate.evaluate(medical, notes.map { it.note })
        if (!safety.allowTrainingGeneration) return CoachReport.Blocked(safety)

        val gateway = gatewayFor(medical, safety)
        val signal = coachAdaptationSignal(notes, symptoms, progress)
        val generated = DeterministicPlanGenerator(gateway).generate(
            userId = userId,
            createdAt = createdAt,
            planId = adaptedPlanId,
            adaptation = signal,
        )
        // Exhaustive over GeneratedPlan (DRE-99). The baseline is the scoliosis-safe
        // PoC subset, so this is Ok in practice — but the gateway is the chokepoint:
        // a future rule change that blocks a baseline movement degrades here to a
        // clean [CoachReport.Unavailable] (keep the original plan), never a 500 and
        // never an unsafe plan surfaced. The red-flag gate already passed, so this
        // is not a medical block.
        val adaptedPlan: TrainingPlan = when (generated) {
            is GeneratedPlan.Ok -> generated.plan
            is GeneratedPlan.Blocked -> return CoachReport.Unavailable(
                originalPlanId = originalPlanId,
                reasons = generated.reasons,
            )
        }

        val fallback = fallbackReport(adaptedPlan, originalPlanId, notes, signal)
        val enriched = enrichReport(fallback, medical, safety, notes, symptoms)
        return enriched
    }

    /**
     * "Спросить у AI" — a short contextual cue for one exercise (NOT a chat).
     * Pre-LLM red-flag gate still applies (a red flag routes to assessment, not a
     * cue). Deterministic fallback = the exercise's library line + a support
     * cue; the provider may enrich the text when it validates.
     */
    fun explain(
        exerciseId: ExerciseId,
        medical: MedicalSafety,
    ): CoachExplain {
        val safety = SafetyGate.evaluate(medical)
        if (!safety.allowTrainingGeneration) return CoachExplain.Blocked(safety)
        val fallback = fallbackExplain(exerciseId)
        if (provider == null) return fallback
        val p = provider
        val raw = runCatching {
            p.complete(
                systemPrompt = COACH_SYSTEM_PROMPT,
                userPayloadJson = explainPayload(exerciseId, medical, safety),
            )
        }.getOrNull() ?: return fallback
        val parsed = parseExplain(raw) ?: return fallback
        return if (validateProviderText(parsed.summaryRu, medical)) {
            CoachExplain.Ok(exerciseId = exerciseId, summaryRu = parsed.summaryRu.trim(), source = CoachSource.LLM)
        } else {
            fallback
        }
    }

    // --- enrichment -----------------------------------------------------------

    /**
     * Calls the provider for a report annotation; on any failure/invalidity the
     * [fallback] stands (source = FALLBACK). The adapted plan is never touched.
     */
    private fun enrichReport(
        fallback: CoachReport.Ok,
        medical: MedicalSafety,
        safety: SafetyEvaluation,
        notes: List<CoachNote>,
        symptoms: List<Symptom>,
    ): CoachReport.Ok {
        if (provider == null) return fallback
        val p = provider
        val raw = runCatching {
            p.complete(
                systemPrompt = COACH_SYSTEM_PROMPT,
                userPayloadJson = reportPayload(fallback.adaptedPlan, notes, symptoms, medical, safety),
            )
        }.getOrNull() ?: return fallback
        val parsed = parseReportAnnotation(raw) ?: return fallback
        // Validate the summary wholesale; corrections are filtered individually so
        // a single out-of-allowlist/banned correction is dropped (not leaked) while
        // a valid summary still reaches the user. A banned summary rejects the
        // whole annotation → fallback.
        if (!validateProviderText(parsed.summaryRu, medical)) return fallback
        val cleanCorrections = parsed.corrections
            .filter { it.exerciseId in BaselineProgram.exerciseIds }
            .filter { validateProviderText(it.noteRu, medical) }
            .take(MAX_CORRECTIONS)
            .map { CoachExerciseCorrection(exerciseId = it.exerciseId, noteRu = it.noteRu.trim()) }
        return fallback.copy(
            summaryRu = parsed.summaryRu.trim(),
            corrections = cleanCorrections,
            source = CoachSource.LLM,
        )
    }

    // --- deterministic fallbacks ---------------------------------------------

    /**
     * The always-available fallback report (#4). The adapted plan is the gated
     * deterministic plan; the text is derived from the user's own notes (pain →
     * "снизим объём"; per-exercise note → a conservative cue) — support-framed,
     * no diagnosis. When the LLM is unavailable this is the full result.
     */
    private fun fallbackReport(
        adaptedPlan: TrainingPlan,
        originalPlanId: String,
        notes: List<CoachNote>,
        signal: AdaptationSignal,
    ): CoachReport.Ok {
        val summary = when (signal) {
            is AdaptationSignal.DeLoad ->
                "Готово: на следующую сессию объём снижен — ${signal.reason}. " +
                    "Приложение поддерживает, а не заменяет врача."
            AdaptationSignal.None ->
                "Готово: следующая сессия идёт по плану. " +
                    "Приложение поддерживает, а не заменяет врача."
        }
        val corrections = notes
            .filter { it.note.isNotBlank() && it.exerciseId in BaselineProgram.exerciseIds }
            .take(MAX_CORRECTIONS)
            .map { n ->
                CoachExerciseCorrection(
                    exerciseId = n.exerciseId,
                    noteRu = fallbackCorrection(n),
                )
            }
        return CoachReport.Ok(
            summaryRu = summary,
            corrections = corrections,
            adaptedPlan = adaptedPlan,
            originalPlanId = originalPlanId,
            source = CoachSource.FALLBACK,
        )
    }

    /** A conservative, support-framed cue derived from one user note (pain → softer load). */
    private fun fallbackCorrection(note: CoachNote): String {
        val name = exerciseName(note.exerciseId)
        return if (NOTE_PAIN.any { it in note.note.lowercase() }) {
            "$name: при дискомфорте снизьте диапазон или темп; при острой/стреляющей боли — прекратите движение."
        } else {
            "$name: сохраняйте нейтральное положение и рабочий темп."
        }
    }

    /** The deterministic exercise cue: name + library line + a support-framed key. */
    private fun fallbackExplain(exerciseId: ExerciseId): CoachExplain.Ok {
        val ex = BaselineProgram.exercises[exerciseId]
        val name = exerciseName(exerciseId)
        val line = if (ex != null) {
            "$name: ${ex.category}, ${ex.repScheme}" + (ex.defaultRir?.let { " @ ${it} RIR" } ?: "")
        } else {
            name
        }
        return CoachExplain.Ok(
            exerciseId = exerciseId,
            summaryRu = "$line. Держите нейтральное положение позвоночника; при дискомфорте снизьте диапазон или вес.",
            source = CoachSource.FALLBACK,
        )
    }

    companion object {
        /** How many per-exercise corrections a report carries (UI cap). */
        const val MAX_CORRECTIONS: Int = 6

        /**
         * The system prompt the coach runs under. Kept in code (not read from
         * disk at runtime) so the server + tests share one source of truth with
         * the validation rules here; the human-readable twin lives at
         * `prompts/coach_prompt_ru.md`. Editing this string is a coach-behaviour
         * change reviewed alongside [validateProviderText].
         */
        val COACH_SYSTEM_PROMPT: String = COACH_SYSTEM_PROMPT_BODY
    }
}

/** A provider's raw phone-readable JSON for a report annotation (the LLM half only). */
@Serializable
private data class ProviderReportAnnotation(
    @SerialName("summary_ru") val summaryRu: String,
    val corrections: List<ProviderCorrection> = emptyList(),
)

@Serializable
private data class ProviderCorrection(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("note_ru") val noteRu: String,
)

@Serializable
private data class ProviderExplain(
    @SerialName("summary_ru") val summaryRu: String,
)

private val coachJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseReportAnnotation(raw: String): ProviderReportAnnotation? =
    runCatching { coachJson.decodeFromString<ProviderReportAnnotation>(raw.stripJsonFence()) }.getOrNull()

private fun parseExplain(raw: String): ProviderExplain? =
    runCatching { coachJson.decodeFromString<ProviderExplain>(raw.stripJsonFence()) }.getOrNull()

/** Tolerates a model that wrapped the JSON in a ```json fence despite the prompt. */
private fun String.stripJsonFence(): String =
    trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

/**
 * The phone-readable report the app renders. [Ok] carries the gated adapted plan
 * + the (fallback-or-LLM) text; [Blocked] is a pre-LLM red-flag block (the gate,
 * not the model). Sealed so the renderer is forced to handle the block path.
 */
@Serializable
sealed interface CoachReport {
    @Serializable
    data class Ok(
        @SerialName("summary_ru") val summaryRu: String,
        val corrections: List<CoachExerciseCorrection>,
        @SerialName("adapted_plan") val adaptedPlan: TrainingPlan,
        @SerialName("original_plan_id") val originalPlanId: String,
        val source: CoachSource,
    ) : CoachReport

    /** Pre-LLM medical-safety gate blocked — route to assessment, no plan surfaced. */
    @Serializable
    data class Blocked(val safety: SafetyEvaluation) : CoachReport

    /**
     * Graceful degrade (DRE-99): the pre-LLM red-flag gate *passed*, but the
     * [dreamteam.domain.safety.SafetyGuardedGateway] blocked a baseline movement
     * (e.g. a future safety-rule tightening marked a baseline exercise as
     * contraindicated), so the all-or-nothing gateway refused the plan rather
     * than leak a hole. NOT a medical block — do not route to assessment. The UI
     * offers "keep the original plan"; [reasons] is gateway block detail for
     * server-side logging/audit and is never rendered as guidance.
     */
    @Serializable
    data class Unavailable(
        @SerialName("original_plan_id") val originalPlanId: String,
        val reasons: List<String>,
    ) : CoachReport
}

/** A short, plain-language per-exercise cue in a [CoachReport]. */
@Serializable
data class CoachExerciseCorrection(
    @SerialName("exercise_id") val exerciseId: ExerciseId,
    @SerialName("note_ru") val noteRu: String,
)

/** The phone-readable exercise-cue result for "Спросить у AI". */
@Serializable
sealed interface CoachExplain {
    @Serializable
    data class Ok(
        @SerialName("exercise_id") val exerciseId: ExerciseId,
        @SerialName("summary_ru") val summaryRu: String,
        val source: CoachSource,
    ) : CoachExplain

    @Serializable
    data class Blocked(val safety: SafetyEvaluation) : CoachExplain
}

/** Where the text came from: the deterministic fallback or the LLM (after validation). */
@Serializable
enum class CoachSource { @SerialName("fallback") FALLBACK, @SerialName("llm") LLM }

/**
 * The LLM port. The single place a provider plugs in — **server-side only**.
 * Returns the raw phone-readable JSON text, or `null` when unavailable / failed
 * / timed out (the coach then keeps the deterministic fallback). Pure interface:
 * no I/O contract beyond "string or null", so it is JVM-testable with a fake.
 */
fun interface CoachProvider {
    fun complete(systemPrompt: String, userPayloadJson: String): String?
}

/**
 * A free-text note the user attached to one exercise in one past session
 * (M8-B [DRE-78](/DRE/issues/DRE-78)). The coach's per-exercise input. Verbatim
 * self-report; never interpreted as a diagnosis.
 */
@Serializable
data class CoachNote(
    @SerialName("exercise_id") val exerciseId: ExerciseId,
    val note: String,
)
