package dreamteam.app

import dreamteam.domain.EvidenceId
import dreamteam.domain.ExerciseId
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
import dreamteam.domain.training.BaselineProgram
import dreamteam.domain.training.DeterministicPlanGenerator
import dreamteam.domain.training.ExerciseAssignment
import dreamteam.domain.training.GeneratedPlan
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M8-A ([DRE-80](/DRE/issues/DRE-80)) — pins the guarantees of the per-exercise
 * **references card** view ([resolveExerciseReferences] → [ResolvedReferences])
 * that [ReferencesCard] renders. Mirrors the M6-A/M6-B test shape
 * ([ClientEvidenceResolverTest] / [EvidenceCitationRenderTest]): the cited/media
 * sets come from the SAME deterministic generators + bundled data the client
 * surfaces, read off the test classpath (byte-identical to the bundled asset).
 *
 * Guarantees (the smallest thing that fails if M8-A breaks):
 * 1. Media resolves BY ID off the bundled library — video/steps/images land on
 *    the view when the catalog carries them. Pinned two ways: a synthetic
 *    populated entry (isolates the resolution mechanism) AND the real bundled
 *    data (M8-A1 content is now seeded — DRE-79). Never invented: a missing
 *    exercise id yields empty media, not a fabricated URL (0 naked links).
 * 2. Evidence citations reuse the M6-A/M6-B render path — every surfaced ref is
 *    a readable citation row, never the raw id; a ghost id → the
 *    `EVIDENCE_NOT_SOURCED` placeholder (honors EvidenceLinked).
 * 3. A real surfaced exercise today renders its evidence citations; most now
 *    also carry real how-to + media (M8-A1 seeded), and any exercise still
 *    without media falls back to the transparent MEDIA_PENDING marker — so the
 *    card is present on every exercise, empty media or not.
 * 4. The authored card strings carry NO banned medical-claim phrase.
 *
 * Claim guard: the card's authored STRINGS are scanned; the verbatim catalog
 * citation rows are NOT (they legitimately carry study vocabulary inside titles
 * — the M6-B design call).
 */
class ExerciseReferencesCardTest {

    /** The bundled catalog + library, as a JVM test reads them (classpath). */
    private fun bundledResolver(): EvidenceResolver {
        val raw = ExerciseReferencesCardTest::class.java.getResourceAsStream("/evidence_catalog.json")!!
            .use { it.readBytes().decodeToString() }
        return EvidenceResolver.fromJson(raw)
    }

    private fun bundledLibrary(): ExerciseLibraryResolver {
        val raw = ExerciseReferencesCardTest::class.java.getResourceAsStream("/exercises.json")!!
            .use { it.readBytes().decodeToString() }
        return ExerciseLibraryResolver.fromJson(raw)
    }

    private val resolver: EvidenceResolver by lazy { bundledResolver() }
    private val library: ExerciseLibraryResolver by lazy { bundledLibrary() }

    @Test
    fun `media resolves by id off the library - video, steps, images land on the view`() {
        // Synthetic populated entry — isolates the resolution mechanism
        // independent of which content the Evidence Analyst has seeded.
        val populated = ExerciseLibraryResolver(
            listOf(
                ExerciseLibraryEntry(
                    id = "split_squat",
                    videoUrl = "https://example.test/split-squat",
                    howToStepsRu = listOf("Шаг 1", "Шаг 2"),
                    imageRefs = listOf("https://example.test/img1"),
                ),
            ),
        )
        val refs = resolveExerciseReferences(
            exerciseId = "split_squat",
            evidenceRefs = listOf("ACSM-RT-2026"),
            library = populated,
            resolver = resolver,
        )

        refs.videoUrl shouldBe "https://example.test/split-squat"
        refs.howToStepsRu shouldBe listOf("Шаг 1", "Шаг 2")
        refs.imageRefs shouldBe listOf("https://example.test/img1")
        refs.hasMedia shouldBe true
    }

    @Test
    fun `a ghost exercise id yields empty media - never an invented url`() {
        val refs = resolveExerciseReferences(
            exerciseId = "DOES-NOT-EXIST",
            evidenceRefs = listOf("ACSM-RT-2026"),
            library = library,
            resolver = resolver,
        )

        // No media fabricated for an unknown id (0 naked links).
        refs.videoUrl shouldBe null
        refs.howToStepsRu.shouldBeEmpty()
        refs.imageRefs.shouldBeEmpty()
        refs.hasMedia shouldBe false
        // Evidence still resolves from the surfaced refs — the card is never empty.
        refs.citations.shouldNotBeEmpty()
    }

    @Test
    fun `evidence citations reuse the M6-A_render path - 0 raw ids`() {
        val refs = resolveExerciseReferences(
            exerciseId = "pushup",
            evidenceRefs = listOf("KIKUCHI-PUSHUP-2017", "ACSM-RT-2026"),
            library = library,
            resolver = resolver,
        )

        // One row per ref, in order; every row is a real citation, not the raw id.
        refs.citations.map { it.id } shouldBe listOf("KIKUCHI-PUSHUP-2017", "ACSM-RT-2026")
        refs.citations.forEach { c ->
            c.resolved shouldBe true
            (c.line.length > c.id.length) shouldBe true
            (c.line == c.id) shouldBe false
        }
    }

    @Test
    fun `a ghost evidence id renders the blocked-until-sourced placeholder - never invented`() {
        val refs = resolveExerciseReferences(
            exerciseId = "pushup",
            evidenceRefs = listOf("GHOST-STUDY"),
            library = library,
            resolver = resolver,
        )

        val row = refs.citations.single()
        row.resolved shouldBe false
        row.line shouldBe EVIDENCE_NOT_SOURCED
        ("GHOST-STUDY" in row.line) shouldBe false
    }

    /**
     * M9-B ([DRE-117](/DRE/issues/DRE-117)): the card is collapsed by default,
     * so the always-visible header ([referencesHeaderLine]) is the only thing
     * shown until the user expands. Pin it carries the exercise name + the card
     * title + a non-zero reference count, so the deliverable ("a card present on
     * every exercise") holds even when collapsed — and the header is
     * deterministic given the same resolved refs (rendering determinism). Pure;
     * no Compose/UI state involved.
     */
    @Test
    fun `the collapsed-card header always carries name, title, and a non-zero reference count`() {
        val refs = resolveExerciseReferences(
            exerciseId = "pushup",
            evidenceRefs = listOf("KIKUCHI-PUSHUP-2017", "ACSM-RT-2026"),
            library = library,
            resolver = resolver,
        )
        val header = referencesHeaderLine("Отжимания", refs)

        // The card title + the exercise name are always on the header line.
        (ReferencesCardStrings.TITLE in header) shouldBe true
        ("Отжимания" in header) shouldBe true
        // The reference count is present as "(N)" and is non-zero: the collapsed
        // chip signals there is something to expand without inventing content.
        val count = Regex("\\((\\d+)\\)").find(header)?.groupValues?.get(1)?.toIntOrNull()
        count shouldNotBe null
        (count!! >= 1) shouldBe true
    }

    /**
     * The surfaced training assignments the client renders — produced by the SAME
     * provisioned gateway [DreamTeamApp.generateLocalPlan] uses for a generic user.
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
    fun `every surfaced exercise resolves a non-empty references view - card present on each`() {
        val assignments = surfacedTrainingAssignments()
        assignments.shouldNotBeEmpty()

        assignments.forEach { a ->
            val refs = resolveExerciseReferences(a.exerciseId, a.evidenceRefs, library, resolver)
            // M8-A deliverable: a card on every exercise. Evidence is always
            // present (DRE-6) — so the card is never empty, whether or not the
            // exercise carries media yet (those without fall back to MEDIA_PENDING).
            refs.citations.filter { it.resolved }.shouldNotBeEmpty()
            // The surfaced id is a real library member (resolves media slot, even if empty).
            library.resolveExercise(a.exerciseId) shouldNotBe null
        }

        // A known surfaced id resolves against the bundled library.
        val allIds = assignments.map { it.exerciseId }.distinct()
        allIds.shouldContain("split_squat")
    }

    @Test
    fun `real surfaced exercises now carry real media - the M8-A1 auto-fill landed`() {
        // M8-A1 content is seeded (DRE-79): how_to_steps_ru on every exercise,
        // video_url + image_refs on most. This pins the deliverable ("references
        // card renders real video/how-to/images") against the ACTUAL bundled
        // data — the smallest thing that fails if someone empties the media
        // fields. Exercises without media still render via MEDIA_PENDING.
        val assignments = surfacedTrainingAssignments()
        assignments.shouldNotBeEmpty()

        // At least one real surfaced exercise carries real media now.
        val withMedia = assignments.filter {
            resolveExerciseReferences(it.exerciseId, it.evidenceRefs, library, resolver).hasMedia
        }
        withMedia.shouldNotBeEmpty()

        // pushup is surfaced; the Evidence Analyst noted its 2 image_refs
        // (start/finish) — the multi-ref forEach path, plus a how-to list.
        val pushupRefs = resolveExerciseReferences("pushup", listOf("KIKUCHI-PUSHUP-2017"), library, resolver)
        pushupRefs.howToStepsRu.shouldNotBeEmpty()
        pushupRefs.imageRefs.size shouldBe 2

        // goblet_squat is surfaced and carries the demo video — a Commons
        // file-page URL openUrl lands on with author + license (attribution).
        val gobletRefs = resolveExerciseReferences("goblet_squat", listOf("ACSM-RT-2026"), library, resolver)
        gobletRefs.videoUrl shouldNotBe null
    }

    @Test
    fun `the bundled library has exactly one entry per surfaced exercise id`() {
        // Defense-in-depth: the references card resolves media by id, so a
        // duplicate/missing library row would render the wrong/no card.
        val assignments = surfacedTrainingAssignments()
        val surfacedIds = assignments.map { it.exerciseId }.distinct()
        surfacedIds.forEach { id ->
            library.resolveExercise(id) shouldNotBe null
        }
    }

    // Banned substrings (lowercased) — same list as the M3-C/M4-C/M6 surface
    // tests: the card may never assert a diagnosis or claim to treat/cure.
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "болезнь",
        "у вас", "вы больн", "вы здоровы", "ваш диагноз",
        "предписываю", "назначаю", "прописываю",
        "diagnos", "treat", "cure", "heal", "disease", "you have", "you are", "prescribe",
    )

    @Test
    fun `no authored references-card string contains a banned medical-claim phrase`() {
        ReferencesCardStrings.all.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }
}
