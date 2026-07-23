package dreamteam.app

import dreamteam.domain.evidence.EvidenceSource
import dreamteam.domain.nutrition.GeneratedNutritionPlan
import dreamteam.domain.nutrition.NutritionGoal
import dreamteam.domain.nutrition.NutritionPlan
import dreamteam.domain.nutrition.NutritionPlanGenerator
import dreamteam.domain.profile.Anthropometrics
import dreamteam.domain.profile.SexForEquations
import dreamteam.domain.safety.ContraindicationStubs
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.StructuralSafetyRules
import dreamteam.domain.training.DeterministicPlanGenerator
import dreamteam.domain.training.BaselineProgram
import dreamteam.domain.training.ExerciseAssignment
import dreamteam.domain.training.GeneratedPlan
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * M6-A ([DRE-67](/DRE/issues/DRE-67)) — pins the three guarantees the pure
 * [EvidenceResolver] must give, enforced as code rather than relied on. Mirrors
 * the server's DRE-14 0-dangling invariant, moved client-side so the
 * offline-first app resolves every surfaced [dreamteam.domain.EvidenceLinked]
 * ref against the bundled catalog (ADR 0001 source of truth) with no network and
 * no invented citation.
 *
 * The resolver is pure (no Android, no I/O — the [ClientNutrition.nutritionPlanView]
 * pattern), so a JVM assertion over it is the smallest sufficient check. The
 * cited id sets come from the SAME deterministic generators the client surfaces
 * through ([DeterministicPlanGenerator] for training assignments,
 * [NutritionPlanGenerator] for the nutrition plan) so the 0-dangling scan covers
 * the real surfaced refs, not a duplicated copy that would drift. The catalog
 * itself is read off the test classpath (build.gradle.kts adds repo-root `data/`
 * as a test resource) — byte-identical to the bundled asset, decoded via the
 * exact server decode ([evidenceJson]).
 */
class ClientEvidenceResolverTest {

    /** The resolver over the bundled catalog, as a JVM test reads it (classpath). */
    private fun bundledResolver(): EvidenceResolver {
        val raw = ClientEvidenceResolverTest::class.java.getResourceAsStream("/evidence_catalog.json")!!
            .use { it.readBytes().decodeToString() }
        return EvidenceResolver.fromJson(raw)
    }

    /**
     * The surfaced training assignments the client renders — produced by the
     * SAME provisioned gateway [generateLocalPlan] uses for a generic (unflagged)
     * user, so every baseline assignment surfaces and we scan the full cited set.
     */
    private fun surfacedTrainingAssignments(): List<ExerciseAssignment> {
        val gateway = SafetyGuardedGateway(
            ScreeningContext(
                allowedExerciseIds = BaselineProgram.exerciseIds,
                allowedEvidenceIds = BaselineProgram.evidenceIds,
            ),
            StructuralSafetyRules.all + ContraindicationStubs.all,
        )
        val plan = DeterministicPlanGenerator(gateway)
            .generate(userId = "local", createdAt = "2026-07-23")
            .shouldBeInstanceOf<GeneratedPlan.Ok>()
            .plan
        return plan.weeks.flatMap { it.sessions }.flatMap { it.assignments }
    }

    /** The surfaced nutrition plan the client renders (a Cunningham-branch profile). */
    private fun surfacedNutritionPlan(): NutritionPlan {
        val gateway = SafetyGuardedGateway(
            ScreeningContext(allowedEvidenceIds = NUTRITION_EVIDENCE_IDS),
            listOf(StructuralSafetyRules.evidenceAllowlist),
        )
        val body = Anthropometrics(
            sex = SexForEquations.MALE,
            ageYears = 28,
            heightCm = 188.0,
            weightKg = 83.2,
            bodyFatPercent = 21.2,
        )
        return NutritionPlanGenerator(gateway)
            .generate(userId = "local", body = body, goal = NutritionGoal.RECOMP, recordedOn = "2026-07-23")
            .shouldBeInstanceOf<GeneratedNutritionPlan.Ok>()
            .plan
    }

    @Test
    fun `every surfaced training + nutrition evidence id resolves to a catalog entry - 0 dangling`() {
        val resolver = bundledResolver()
        val trainingRefs = surfacedTrainingAssignments().flatMap { it.evidenceRefs }
        val nutritionRefs = surfacedNutritionPlan().evidenceRefs
        val allRefs = (trainingRefs + nutritionRefs).distinct()

        // Sanity: both surfaced sets actually carry refs, else the check is vacuous.
        trainingRefs.shouldNotBeEmpty()
        nutritionRefs.shouldNotBeEmpty()

        // 0 dangling client-side (mirrors the DRE-14 server invariant): every
        // surfaced EvidenceLinked ref resolves to a catalog entry — no orphans.
        val dangling = allRefs.filter { resolver.resolveEvidence(it) == null }
        dangling.shouldBeEmpty()

        // The union of surfaced ids is a real, non-empty subset of the catalog.
        allRefs.shouldContain("ACSM-RT-2026") // a known training id
        allRefs.shouldContain("MORTON-PROTEIN-2018") // a known nutrition id
    }

    @Test
    fun `resolution is deterministic - same id yields the same EvidenceSource`() {
        val resolver = bundledResolver()
        val id = "ACSM-RT-2026"

        val a: EvidenceSource? = resolver.resolveEvidence(id)
        val b: EvidenceSource? = resolver.resolveEvidence(id)

        a shouldNotBe null
        // Run twice → value-equal and referentially identical (one cached instance).
        a shouldBe b
        a shouldBeSameInstanceAs b
        // And the entry is a real decoded source, not a blank stub.
        ("American College of Sports Medicine" in a!!.citation) shouldBe true
        a.evidenceLevel shouldBe "high"
    }

    @Test
    fun `a ghost id resolves to null - never an invented citation`() {
        val resolver = bundledResolver()

        // An unresolved/ghost id is null, never a fabricated citation (honors
        // EvidenceLinked: the caller renders a blocked-until-sourced placeholder).
        resolver.resolveEvidence("GHOST-STUDY") shouldBe null
    }
}
