package dreamteam.app

import dreamteam.domain.safety.ContraindicationStubs
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.StructuralSafetyRules
import dreamteam.domain.training.BaselineProgram
import dreamteam.domain.training.DeterministicPlanGenerator
import dreamteam.domain.training.ExerciseAssignment
import dreamteam.domain.training.GeneratedPlan
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Iter 2 ([DRE-260](/DRE/issues/DRE-260)): pins the contract of [exerciseDensityChips]
 * (single prescription chip) + [exerciseMetaChips] (detail-only equipment/evidence
 * chips). Pure JVM — no Android, no device needed.
 */
class ExerciseDensityChipTest {

    private fun bundledResolver(): EvidenceResolver =
        EvidenceResolver.fromJson(
            ExerciseDensityChipTest::class.java.getResourceAsStream("/evidence_catalog.json")!!
                .use { it.readBytes().decodeToString() },
        )

    private fun bundledLibrary(): ExerciseLibraryResolver =
        ExerciseLibraryResolver.fromJson(
            ExerciseDensityChipTest::class.java.getResourceAsStream("/exercises.json")!!
                .use { it.readBytes().decodeToString() },
        )

    private val resolver: EvidenceResolver by lazy { bundledResolver() }
    private val library: ExerciseLibraryResolver by lazy { bundledLibrary() }

    @Test
    fun `exerciseDensityChips emits a single prescription chip`() {
        // Full case: sets + repScheme + RIR → "3×8-12 @2 RIR"
        val full = exerciseDensityChips(
            sets = 3,
            repScheme = "8-12",
            rir = 2,
        )
        full.label shouldBe "3×8-12 @2 RIR"

        // No RIR: "1×20-40 мин"
        val noRir = exerciseDensityChips(1, "20-40 мин", null)
        noRir.label shouldBe "1×20-40 мин"

        // Blank repScheme → omit ×repScheme segment: "3 @2 RIR"
        val blankReps = exerciseDensityChips(3, "", 2)
        blankReps.label shouldBe "3 @2 RIR"

        // Blank repScheme + no RIR → sets only: "3"
        val setsOnly = exerciseDensityChips(3, "", null)
        setsOnly.label shouldBe "3"

        // Determinism: same inputs → same chip
        exerciseDensityChips(3, "8-12", 2) shouldBe full
    }

    @Test
    fun `exerciseMetaChips surfaces equipment and evidence levels, suppressed when blank or none`() {
        // Equipment + evidence present
        val meta = exerciseMetaChips("dumbbell", listOf("moderate", "high", "moderate"))
        meta.map { it.label } shouldBe listOf("dumbbell", "moderate", "high")

        // null equipment → omitted; empty evidence → omitted
        val minimal = exerciseMetaChips(null, emptyList())
        minimal shouldBe emptyList()

        // "none" equipment suppressed
        val noneEq = exerciseMetaChips("none", emptyList())
        noneEq shouldBe emptyList()

        // Blank equipment suppressed, evidence levels kept
        val blankEq = exerciseMetaChips("  ", listOf("moderate"))
        blankEq.map { it.label } shouldBe listOf("moderate")
    }

    @Test
    fun `every surfaced exercise resolves a non-empty prescription chip and meta chips`() {
        val assignments = surfacedTrainingAssignments()
        assignments.shouldNotBeEmpty()

        assignments.forEach { a ->
            val refs = resolveExerciseReferences(a.exerciseId, a.evidenceRefs, library, resolver)
            val chip = exerciseDensityChips(a.sets, a.repScheme, a.rir)
            // Prescription chip always non-empty (at minimum the sets number).
            chip.label.isNotBlank() shouldBe true
            chip.label shouldContain "${a.sets}"
            // Evidence-linked (DRE-6): every surfaced exercise resolves ≥1 level.
            refs.evidenceLevels.shouldNotBeEmpty()
            (refs.equipment != null) shouldBe true
        }

        // Pushup resolves real equipment.
        val pushupRefs = resolveExerciseReferences("pushup", listOf("KIKUCHI-PUSHUP-2017"), library, resolver)
        pushupRefs.equipment shouldBe "floor/table/blocks"
    }

    /**
     * The surfaced training assignments the client renders — produced by the SAME
     * provisioned gateway [DreamTeamApp.generateLocalPlan] uses for a generic user
     * (same helper as [ExerciseReferencesCardTest]).
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

    // Banned substrings (lowercased) — same list as the M3-C/M4-C/M9-B surface
    // tests: the chip strings may never assert a diagnosis or claim to treat/cure.
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "болезнь",
        "у вас", "вы больн", "вы здоровы", "ваш диагноз",
        "предписываю", "назначаю", "прописываю",
        "diagnos", "treat", "cure", "heal", "disease", "you have", "you are", "prescribe",
    )

    @Test
    fun `no authored density-chip string contains a banned medical-claim phrase`() {
        DensityChipStrings.all.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }
}
