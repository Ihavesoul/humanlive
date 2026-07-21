package dreamteam.server.plan

import dreamteam.domain.evidence.EvidenceSource
import dreamteam.domain.safety.MedicalSafety
import dreamteam.domain.safety.Recommendation
import dreamteam.domain.safety.RuleStatus
import dreamteam.domain.safety.RuleTrigger
import dreamteam.domain.safety.SafetyEvaluation
import dreamteam.domain.safety.SafetyGate
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.SafetyRule
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.SurfacedPlan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The deterministic, no-LLM fallback plan — ADR 0001 invariant #4: shipped first
 * by design. Loads the vetted PoC baseline (programme sessions + exercise
 * library + evidence catalog) from the embedded `data/` resources, and:
 *
 *  - exposes the candidate [Recommendation]s the gateway vets,
 *  - the block-by-default [ScreeningContext] + ACTIVE allowlist rules that
 *    provision the gateway (empty ruleset => everything blocks, DRE-7),
 *  - [generate], the single orchestration that runs the pre-LLM [SafetyGate]
 *    then [SafetyGuardedGateway.surface], and
 *  - [render], which builds the schema-valid response reading **only** from
 *    [SurfacedPlan.surfaced] — the sole legal producer of that type.
 *
 * Nothing here authors clinical content: the baseline is the Evidence Analyst's
 * vetted set (research/evidence_mapping.md), the allowlist rules are the
 * engineering enforcement of ADR 0001 #2 (citations resolve only from the
 * catalog), and contraindication rules are the Safety Reviewer's
 * ([dreamteam.domain.safety.ContraindicationStubs]) — DRAFT/inert until
 * activated, so they never bypass or unblock a candidate.
 */
object BaselinePlan {

    // ignoreUnknownKeys: the PoC `data/*.json` carry RU-only / extra fields we
    // do not consume (see research/evidence_mapping.md spec note).
    private val json = Json { ignoreUnknownKeys = true }

    private val exercises: Map<String, ExerciseSeed> =
        load("/exercises.json") { json.decodeFromString<List<ExerciseSeed>>(it) }.associateBy { it.id }

    private val evidenceCatalog: Map<String, EvidenceSource> =
        load("/evidence_catalog.json") { json.decodeFromString<List<EvidenceSource>>(it) }.associateBy { it.id }

    private val program: ProgramSeed =
        load("/program_12_weeks.json") { json.decodeFromString<ProgramSeed>(it) }

    /** Distinct exercises across the rendered baseline sessions, in first-seen order. */
    val candidateExerciseIds: List<String> = run {
        val seen = LinkedHashSet<String>()
        for (sid in scheduledSessionIds()) {
            val s = program.sessions[sid] ?: continue
            s.warmup.forEach(seen::add); s.main.forEach(seen::add)
        }
        seen.toList()
    }

    /** Baseline candidates the gateway vets. evidenceRefs resolve to the catalog (0 dangling — DRE-14). */
    fun candidates(): List<Recommendation> = candidateExerciseIds.map { id ->
        val seed = exercises[id] ?: error("baseline exercise '$id' has no library entry")
        Recommendation(exerciseId = id, evidenceRefs = seed.evidenceIds)
    }

    /** The exercise allowlist = every baseline exercise id (the vetted set). */
    val allowedExerciseIds: Set<String> get() = exercises.keys

    /** The evidence allowlist = every catalog id (the model may cite only these — ADR 0001 #2). */
    val allowedEvidenceIds: Set<String> get() = evidenceCatalog.keys

    /**
     * Block-by-default provisioning: ACTIVE allowlist rules that make the
     * exercise + evidence allowlists binding through the gateway. Registered =>
     * the gateway is provisioned; with no ACTIVE rule it blocks everything.
     * No clinical rule is authored here (contraindication coverage is the Safety
     * Reviewer's, DRAFT until activated — never unblocks a candidate).
     */
    fun activeAllowlistRules(): List<SafetyRule> = listOf(
        SafetyRule(
            id = "baseline_exercise_allowlist",
            description = "Deterministic fallback: only baseline-vetted exercises may surface (ADR 0001 #2).",
            trigger = RuleTrigger.ExerciseNotInAllowlist("__not_allowlisted__"),
            decision = SafetyRule.Decision.BLOCK,
            evidenceRefs = listOf("SAFETY-ALLOWLIST"),
            status = RuleStatus.ACTIVE,
        ),
        SafetyRule(
            id = "baseline_evidence_allowlist",
            description = "Deterministic fallback: every citation must resolve to the evidence catalog (ADR 0001 #2).",
            trigger = RuleTrigger.EvidenceNotInAllowlist("__not_allowlisted__"),
            decision = SafetyRule.Decision.BLOCK,
            evidenceRefs = listOf("SAFETY-ALLOWLIST"),
            status = RuleStatus.ACTIVE,
        ),
    )

    /**
     * The single orchestration for `POST /v1/plans/generate`. Runs the pre-LLM
     * red-flag gate ([SafetyGate]) then [SafetyGuardedGateway.surface] over the
     * baseline candidates. The returned [PlanResponse] is produced solely from
     * [SurfacedPlan.surfaced] (via [render]) — there is no path that surfaces a
     * recommendation the gateway did not vet.
     */
    fun generate(medicalSafety: MedicalSafety): PlanResponse {
        val eval = SafetyGate.evaluate(medicalSafety)
        if (!eval.allowTrainingGeneration) {
            return blocked(eval, "blocked_red_flag")
        }
        val ctx = ScreeningContext(
            allowedExerciseIds = allowedExerciseIds,
            allowedEvidenceIds = allowedEvidenceIds,
            // Inert today: no ACTIVE contraindication rule is registered, so this
            // flag cannot block anything. It matters once the Safety Reviewer
            // activates ContraindicationStubs (DRE-10); derived conservatively.
            conditionFlags = scoliosisFlag(medicalSafety),
        )
        val gateway = SafetyGuardedGateway(ctx, activeAllowlistRules())
        val plan = gateway.surface(candidates())
        return when (plan) {
            is SurfacedPlan.Ok -> render(plan.surfaced, eval)
            is SurfacedPlan.Blocked -> blocked(eval, "needs_clinician_input")
        }
    }

    /** Renders a schema-valid (workout_response.schema.json) Ok response from the surfaced set only. */
    private fun render(surfaced: List<Recommendation>, eval: SafetyEvaluation): PlanResponse {
        val surfacedIds = surfaced.map { it.exerciseId }.toSet()
        val sessions = scheduledSessionIds().mapNotNull { sid ->
            val s = program.sessions[sid] ?: return@mapNotNull null
            val ex = (s.warmup + s.main).mapNotNull { id ->
                if (id !in surfacedIds) null else exercises[id]?.let { seed ->
                    PlanExercise(
                        exerciseId = id,
                        sets = seed.defaultSets,
                        repTarget = seed.repScheme,
                        rirTarget = seed.rir,
                        evidenceIds = seed.evidenceIds,
                        notes = seed.scoliosisRule.take(800),
                    )
                }
            }
            PlanSession(sid, s.titleRu, s.durationMin, ex)
        }
        val surfacedEvidence = surfaced.flatMap { it.evidenceRefs }.toSet()
        val citations = surfacedEvidence.mapNotNull { id ->
            evidenceCatalog[id]?.let { PlanCitation(id, it.keyFinding.take(500)) }
        }
        val uncertainties = surfacedEvidence
            .mapNotNull { evidenceCatalog[it]?.limitations?.takeIf(String::isNotBlank)?.take(500) }
            .distinct()
        return PlanResponse(
            schemaVersion = "1.0.0",
            status = "ok",
            safety = PlanSafety(eval.redFlagGatePassed, eval.allowSideSpecificContent, eval.warnings),
            programme = PlanProgramme(weeks = 12, sessions = sessions),
            citations = citations,
            uncertainties = uncertainties,
        )
    }

    private fun blocked(eval: SafetyEvaluation, status: String): PlanResponse = PlanResponse(
        schemaVersion = "1.0.0",
        status = status,
        safety = PlanSafety(eval.redFlagGatePassed, sideSpecificContentEnabled = false, eval.warnings),
        programme = PlanProgramme(weeks = 1, sessions = emptyList()),
        citations = emptyList(),
        uncertainties = emptyList(),
    )

    /**
     * Conservative `scoliosis_flagged` derivation matching ContraindicationStubs'
     * spec (Cobb >= ~30 deg / rigid-structural / braced): flag when reported AND
     * current curve data is absent OR any Cobb >= 30. Inert until a
     * contraindication rule is activated (Safety Reviewer, DRE-10).
     */
    private fun scoliosisFlag(m: MedicalSafety): Set<String> {
        if (!m.scoliosisReported) return emptySet()
        val cobb = m.currentCobbAnglesDeg.maxOrNull()
        val flagged = !m.currentCurveDataAvailable || (cobb != null && cobb >= 30.0)
        return if (flagged) setOf("scoliosis_flagged") else emptySet()
    }

    /** Default-week schedule, in day order, limited to defined sessions. */
    private fun scheduledSessionIds(): List<String> =
        program.defaultWeekSchedule.map { it.sessionId }.distinct().filter { it in program.sessions }

    private inline fun <T> load(path: String, decode: (String) -> T): T =
        decode(BaselinePlan::class.java.getResourceAsStream(path)!!.use { it.readBytes().decodeToString() })
}

// ---- PoC seed DTOs (snake_case load shape; read-only, NOT the domain model) ----

@Serializable
private data class ExerciseSeed(
    val id: String,
    @SerialName("default_sets") val defaultSets: Int,
    @SerialName("rep_scheme") val repScheme: String,
    val rir: Int? = null,
    @SerialName("evidence_ids") val evidenceIds: List<String>,
    @SerialName("name_ru") val nameRu: String,
    @SerialName("scoliosis_rule") val scoliosisRule: String,
)

@Serializable
private data class ProgramSeed(
    @SerialName("default_week_schedule") val defaultWeekSchedule: List<DaySlot>,
    val sessions: Map<String, SessionSeed>,
)

@Serializable
private data class DaySlot(val day: String, @SerialName("session_id") val sessionId: String)

@Serializable
private data class SessionSeed(
    @SerialName("title_ru") val titleRu: String,
    @SerialName("duration_min") val durationMin: Int,
    val warmup: List<String> = emptyList(),
    val main: List<String> = emptyList(),
)

// ---- Request/response DTOs (validate against data/schemas/*.schema.json) ----

/**
 * Thin request wrapper for `/v1/plans/generate`. A full `workout_request`
 * deserializes cleanly (ignoreUnknownKeys); the deterministic fallback consumes
 * only [medicalSafety]. profile/schedule/equipment drive the LLM path (follow-up).
 */
@Serializable
data class PlanGenerateRequest(
    @SerialName("medical_safety") val medicalSafety: MedicalSafety = MedicalSafety(),
)

@Serializable
data class PlanResponse(
    @SerialName("schema_version") val schemaVersion: String,
    val status: String,
    val safety: PlanSafety,
    val programme: PlanProgramme,
    val citations: List<PlanCitation>,
    val uncertainties: List<String>,
)

@Serializable
data class PlanSafety(
    @SerialName("red_flag_gate_passed") val redFlagGatePassed: Boolean,
    @SerialName("side_specific_content_enabled") val sideSpecificContentEnabled: Boolean,
    val warnings: List<String>,
)

@Serializable
data class PlanProgramme(
    val weeks: Int,
    val sessions: List<PlanSession>,
)

@Serializable
data class PlanSession(
    @SerialName("session_id") val sessionId: String,
    val title: String,
    @SerialName("duration_min") val durationMin: Int,
    val exercises: List<PlanExercise>,
)

@Serializable
data class PlanExercise(
    @SerialName("exercise_id") val exerciseId: String,
    val sets: Int,
    @SerialName("rep_target") val repTarget: String,
    @SerialName("rir_target") val rirTarget: Int?,
    val tempo: String? = null,
    @SerialName("rest_seconds") val restSeconds: Int? = null,
    @SerialName("evidence_ids") val evidenceIds: List<String>,
    val notes: String? = null,
)

@Serializable
data class PlanCitation(
    @SerialName("evidence_id") val evidenceId: String,
    val claim: String,
)
