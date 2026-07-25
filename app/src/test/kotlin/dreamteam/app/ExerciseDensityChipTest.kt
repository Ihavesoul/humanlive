package dreamteam.app

import dreamteam.domain.ExerciseId
import dreamteam.domain.safety.ContraindicationStubs
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.StructuralSafetyRules
import dreamteam.domain.training.BaselineProgram
import dreamteam.domain.training.DeterministicPlanGenerator
import dreamteam.domain.training.ExerciseAssignment
import dreamteam.domain.training.GeneratedPlan
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M9-C ([DRE-120](/DRE/issues/DRE-120)) — pins the guarantees of the denser
 * exercise-card metadata chips ([exerciseDensityChips] → [DensityChip]) that
 * [SessionCard] renders, plus the [ResolvedReferences] fields it reads
 * ([equipment], [evidenceLevels]). Mirrors the M9-B test shape
 * ([ExerciseReferencesCardTest]): the chips come from the SAME deterministic
 * generators + bundled data the client surfaces, read off the test classpath.
 *
 * Guarantees (the smallest thing that fails if M9-C breaks):
 * 1. [exerciseDensityChips] is a pure, deterministic render of its inputs —
 *    sets + reps always present, RIR only when non-null, equipment only when the
 *    catalog carries a non-blank / non-"none" value, one tag per distinct
 *    evidence level. Same inputs → same chips (rendering determinism).
 * 2. Every surfaced training assignment resolves a non-empty chip list (sets is
 *    always there), AND every surfaced id resolves its equipment + at least one
 *    evidence level off the bundled library/catalog — so the denser card has the
 *    same data parity as the M8-A references card (no regression).
 * 3. The authored chip strings carry NO banned medical-claim phrase.
 *
 * Claim guard: only the app-authored [DensityChipStrings] are scanned; the
 * verbatim catalog-vocab tag VALUES (equipment / evidenceLevel) are NOT — they
 * are the Evidence & Research Analyst's controlled vocabulary, not app copy
 * (same stance as the M6-B citation-row claim guard).
 */
class ExerciseDensityChipTest {

    /** The bundled catalog + library, as a JVM test reads them (classpath). */
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
    fun `chips are a deterministic render of inputs - sets and reps always, rir and equipment conditional`() {
        // sets + reps always; RIR present; non-none equipment present.
        val chips = exerciseDensityChips(
            sets = 3,
            repScheme = "8-12",
            rir = 2,
            equipment = "dumbbell",
            evidenceLevels = listOf("moderate"),
        )
        chips.map { it.label } shouldBe listOf(
            "3 ${DensityChipStrings.SETS}",
            "8-12 ${DensityChipStrings.REPS}",
            "${DensityChipStrings.RIR} 2",
            "dumbbell",
            "moderate",
        )

        // Null RIR + blank/none equipment + empty evidence levels → sets + reps only.
        val minimal = exerciseDensityChips(
            sets = 1,
            repScheme = "20-40 мин",
            rir = null,
            equipment = null,
            evidenceLevels = emptyList(),
        )
        minimal.map { it.label } shouldBe listOf(
            "1 ${DensityChipStrings.SETS}",
            "20-40 мин ${DensityChipStrings.REPS}",
        )

        // "none" equipment is the one value suppressed (no-equipment movements
        // render no equipment tag rather than a "none" chip).
        val noneEquipped = exerciseDensityChips(1, "5-8", null, "none", emptyList())
        noneEquipped.map { it.label } shouldContain "1 ${DensityChipStrings.SETS}"
        noneEquipped.any { it.label == "none" } shouldBe false

        // Same inputs → same chips (pure / rendering determinism).
        exerciseDensityChips(3, "8-12", 2, "dumbbell", listOf("moderate")) shouldBe chips
    }

    @Test
    fun `one evidence-level tag per distinct resolved level, deduped`() {
        val chips = exerciseDensityChips(3, "8-12", 2, "mat", listOf("moderate", "high", "moderate"))
        // "moderate" appears once even though cited twice — a label of what is
        // there, not a count/appraisal.
        chips.filter { it.label == "moderate" }.size shouldBe 1
        chips.map { it.label } shouldContain "high"
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

    @Test
    fun `every surfaced exercise resolves a non-empty chip list and its equipment + evidence levels`() {
        // Data parity with M8-A: the denser card surfaces the same data the
        // references card does. Sets is always present → chips never empty; every
        // surfaced id resolves equipment (library) + at least one evidence level
        // (catalog) so the tags are populated, not blank.
        val assignments = surfacedTrainingAssignments()
        assignments.shouldNotBeEmpty()

        assignments.forEach { a ->
            val refs = resolveExerciseReferences(a.exerciseId, a.evidenceRefs, library, resolver)
            val chips = exerciseDensityChips(a.sets, a.repScheme, a.rir, refs.equipment, refs.evidenceLevels)
            chips.shouldNotBeEmpty()
            // Sets is the one always-present chip.
            chips.map { it.label } shouldContain "${a.sets} ${DensityChipStrings.SETS}"
            // Evidence-linked (DRE-6): every surfaced exercise resolves ≥1 level.
            refs.evidenceLevels.shouldNotBeEmpty()
            // The library carries an equipment vocab for every surfaced id
            // (data/exercises.json has `equipment` on every entry).
            (refs.equipment != null) shouldBe true
        }

        // A known surfaced id resolves its real catalog equipment (pushup →
        // floor/table/blocks) — pins the library→refs wiring didn't silently drop.
        val pushupRefs = resolveExerciseReferences("pushup", listOf("KIKUCHI-PUSHUP-2017"), library, resolver)
        pushupRefs.equipment shouldBe "floor/table/blocks"
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
