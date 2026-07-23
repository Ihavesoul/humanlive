package dreamteam.app

import dreamteam.domain.safety.Recommendation
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.SurfacedPlan
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M6-C ([DRE-69](/DRE/issues/DRE-69)) — pins the guarantees of the explainable-
 * safety-block surface ([safetyBlockExplanation]), enforced as code rather than
 * relied on. The mission promises an app that "blocks unsafe guidance"; this
 * slice makes the *block itself* transparent by resolving the blocking
 * [dreamteam.domain.safety.SafetyRule]'s `evidenceRefs` to readable citations.
 *
 * Mirrors the M6-A/M6-B ([DRE-67](/DRE/issues/DRE-67) / [DRE-68](/DRE/issues/DRE-68))
 * render-path tests: the cited sets come from the SAME rule list the client
 * provisions ([CLIENT_SAFETY_RULES]), and the catalog is read off the test
 * classpath (byte-identical to the bundled asset). The explanation is pure, so a
 * JVM assertion is the smallest sufficient check of the Compose surface.
 *
 * Guarantees (the smallest thing that fails if M6-C breaks):
 * 1. A block from a **contraindication** rule surfaces ≥1 RESOLVED citation
 *    (real author/year), never a raw id — the block reads "Основание: <citation>".
 * 2. A block from a **synthetic allowlist** rule renders the `EVIDENCE_NOT_SOURCED`
 *    placeholder (the guardrail id is intentionally NOT in the catalog, ADR 0001
 *    §1) — never an invented citation. This is the intended transparency UX for a
 *    structural-guardrail block, not a gap to fill.
 * 3. The gate's block behavior is UNCHANGED: a blocked plan still surfaces
 *    nothing as guidance (`surfaced == []`). Citations explain the block; they
 *    are not rendered guidance.
 * 4. The app-authored block framing carries no banned medical-claim phrase
 *    (crude-substring scan over [SafetyBlockStrings.all] only — NOT over verbatim
 *    catalog citation text, which legitimately carries study vocabulary).
 */
class SafetyBlockExplanationTest {

    /** The resolver over the bundled catalog, as a JVM test reads it (classpath). */
    private val resolver: EvidenceResolver =
        EvidenceResolver.fromJson(
            SafetyBlockExplanationTest::class.java.getResourceAsStream("/evidence_catalog.json")!!
                .use { it.readBytes().decodeToString() },
        )

    /**
     * A contraindication block (scoliosis_flagged + a `heavy_axial_loading`
     * movement tag) produced by the SAME provisioned gateway the client builds,
     * carrying the triggering rule id for citation lookup.
     */
    private fun contraindicationBlock(ruleIdsOut: (List<String>) -> Unit): SurfacedPlan.Blocked {
        val gateway = SafetyGuardedGateway(
            ScreeningContext(
                // Exercise + evidence ARE allowlisted, so only the contraindication
                // rule (tag + condition flag) matches — not the structural guardrails.
                allowedExerciseIds = setOf("squat"),
                allowedEvidenceIds = setOf("WEINSTEIN-AIS-2008"),
                conditionFlags = setOf("scoliosis_flagged"),
            ),
            CLIENT_SAFETY_RULES,
        )
        val rec = Recommendation(
            exerciseId = "squat",
            evidenceRefs = listOf("WEINSTEIN-AIS-2008"),
            exerciseTags = setOf("heavy_axial_loading"),
        )
        val blocked = gateway.surface(listOf(rec)).shouldBeInstanceOf<SurfacedPlan.Blocked>()
        ruleIdsOut(blocked.items.flatMap { it.verdict.ruleIds })
        return blocked
    }

    /** A structural-allowlist block: an exercise id NOT in the allowlist. */
    private fun structuralBlock(ruleIdsOut: (List<String>) -> Unit): SurfacedPlan.Blocked {
        val gateway = SafetyGuardedGateway(
            ScreeningContext(allowedExerciseIds = setOf("only_this_one")),
            CLIENT_SAFETY_RULES,
        )
        val rec = Recommendation(exerciseId = "NOT_LISTED", evidenceRefs = emptyList())
        val blocked = gateway.surface(listOf(rec)).shouldBeInstanceOf<SurfacedPlan.Blocked>()
        ruleIdsOut(blocked.items.flatMap { it.verdict.ruleIds })
        return blocked
    }

    @Test
    fun `a contraindication block surfaces at least one resolved citation with a real author - not a raw id`() {
        var ruleIds: List<String> = emptyList()
        contraindicationBlock { ruleIds = it }

        // The contraindication rule fired (not a structural guardrail).
        ruleIds.shouldContain("stub_heavy_axial_loading_scoliosis")

        val explanation = safetyBlockExplanation(SafetyBlockStrings.GATEWAY_HEADLINE, ruleIds, CLIENT_SAFETY_RULES, resolver)
        explanation.reason shouldBe SafetyBlockStrings.GATEWAY_HEADLINE
        explanation.citations.shouldNotBeEmpty()

        // M6-C core: ≥1 RESOLVED citation (real author/year), never the raw id.
        val resolved = explanation.citations.filter { it.resolved }
        resolved.shouldNotBeEmpty()
        val weinstein = resolved.first { it.id == "WEINSTEIN-AIS-2008" }
        ("Weinstein" in weinstein.line) shouldBe true
        // The evidenceLevel label is rendered verbatim (a label, not an appraisal).
        ("уровень: moderate_context" in weinstein.line) shouldBe true
        // The line is the readable citation, never the raw id alone.
        (weinstein.line == weinstein.id) shouldBe false
    }

    @Test
    fun `a flexion-rotation block surfaces both sourced studies as resolved citations`() {
        // The flexion-rotation slot cites a two-source bundle — both resolve.
        val ctx = ScreeningContext(
            allowedExerciseIds = setOf("twist"),
            allowedEvidenceIds = setOf("MARSHALL-MCGILL-AXIAL-TORQUE-2010", "MARRAS-TRUNK-MOTION-1993"),
            conditionFlags = setOf("scoliosis_flagged"),
        )
        val gateway = SafetyGuardedGateway(ctx, CLIENT_SAFETY_RULES)
        val rec = Recommendation(
            exerciseId = "twist",
            evidenceRefs = listOf("MARSHALL-MCGILL-AXIAL-TORQUE-2010", "MARRAS-TRUNK-MOTION-1993"),
            exerciseTags = setOf("loaded_flexion_rotation"),
        )
        val ruleIds = gateway.surface(listOf(rec))
            .shouldBeInstanceOf<SurfacedPlan.Blocked>()
            .items.flatMap { it.verdict.ruleIds }

        val explanation = safetyBlockExplanation(SafetyBlockStrings.GATEWAY_HEADLINE, ruleIds, CLIENT_SAFETY_RULES, resolver)
        // Both cited ids surface as RESOLVED citations (no raw id, no placeholder).
        explanation.citations.map { it.id } shouldBe listOf("MARSHALL-MCGILL-AXIAL-TORQUE-2010", "MARRAS-TRUNK-MOTION-1993")
        explanation.citations.forEach { c ->
            c.resolved shouldBe true
            (c.line == c.id) shouldBe false
        }
    }

    @Test
    fun `a synthetic allowlist block renders the blocked-until-sourced placeholder - never an invented citation`() {
        var ruleIds: List<String> = emptyList()
        structuralBlock { ruleIds = it }

        // The structural exercise-allowlist rule fired (synthetic guardrail id).
        ruleIds.shouldContain("structural_exercise_allowlist")

        val explanation = safetyBlockExplanation(SafetyBlockStrings.GATEWAY_HEADLINE, ruleIds, CLIENT_SAFETY_RULES, resolver)

        // SAFETY-STRUCTURAL-ALLOWLIST is intentionally NOT in the catalog (ADR
        // 0001 §1) → exactly one citation, the placeholder, no invented citation.
        explanation.citations.size shouldBe 1
        val row = explanation.citations.single()
        row.resolved shouldBe false
        row.line shouldBe EVIDENCE_NOT_SOURCED
        // No raw guardrail id and no fabricated author/year leaks in.
        ("SAFETY-STRUCTURAL-ALLOWLIST" in row.line) shouldBe false
    }

    @Test
    fun `no ruleIds - the medical-safety path - surfaces no citations, only the headline`() {
        // The red-flag gate (MedicalSafety) is a different gate with no SafetyRule:
        // empty ruleIds → empty citations → the card shows the headline only.
        val explanation = safetyBlockExplanation(SafetyBlockStrings.REDFLAG_HEADLINE, emptyList(), CLIENT_SAFETY_RULES, resolver)
        explanation.reason shouldBe SafetyBlockStrings.REDFLAG_HEADLINE
        explanation.citations.shouldBeEmpty()
    }

    @Test
    fun `a blocked plan still surfaces nothing as guidance - the gate invariant is unchanged`() {
        var ruleIds: List<String> = emptyList()
        val blocked = contraindicationBlock { ruleIds = it }

        // M6-C does NOT touch the gate: a block surfaces NOTHING as guidance.
        // Citations are explanation of the block, computed from the rule's
        // evidenceRefs — they are independent of (and never leak) the surfaced list.
        blocked.surfaced.shouldBeEmpty()
        // The explanation carries the headline + citations only; it adds nothing
        // to the surfaced guidance.
        val explanation = safetyBlockExplanation(SafetyBlockStrings.GATEWAY_HEADLINE, ruleIds, CLIENT_SAFETY_RULES, resolver)
        explanation.citations.map { it.id }.shouldContain("WEINSTEIN-AIS-2008")
        blocked.surfaced.shouldBeEmpty()
    }

    // Banned substrings (lowercased) — same list as the M3-C/M4-C/M5-B/M6-B
    // surface tests: the block framing may never assert a diagnosis, claim to
    // treat/cure, or frame the user in second-person clinical terms.
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "болезнь",
        "у вас", "вы больн", "вы здоровы", "ваш диагноз",
        "предписываю", "назначаю", "прописываю",
        "diagnos", "treat", "cure", "heal", "disease", "you have", "you are", "prescribe",
    )

    @Test
    fun `no APP-authored block-framing string contains a banned medical-claim phrase`() {
        // Crude-substring scan over ONLY the strings the app authors (the block
        // headlines + the citation label) — NOT the verbatim catalog citation
        // rows, which legitimately carry study vocabulary ("health",
        // "prescription", "treatment") inside study titles/findings. Crude-
        // scanning catalog text would false-positive on vetted evidence.
        SafetyBlockStrings.all.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
        // Sanity: the label that prefixes citations is present and non-blank.
        SafetyBlockStrings.CITATION_LABEL.isNotBlank() shouldBe true
    }
}
