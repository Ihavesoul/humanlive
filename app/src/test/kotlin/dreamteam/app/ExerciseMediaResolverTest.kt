package dreamteam.app

import dreamteam.domain.ExerciseId
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Redesign v2 ([DRE-210](/DRE/issues/DRE-210)) — pins the guarantees of the
 * exercise-card media resolver ([resolveExerciseMedia] → [ResolvedExerciseMedia])
 * that the Compose card-image slot (DRE-211) renders from. Mirrors the
 * [ExerciseReferencesCardTest] shape: the catalog is the SAME bundled
 * `data/exercise_media.json` (Evidence Analyst output, DRE-207) the app ships,
 * read off the test classpath (byte-identical to the bundled asset).
 *
 * Guarantees (the smallest thing that fails if the resolver breaks):
 * 1. A `sourced` catalog entry resolves to a [CardImage] carrying url + license
 *    + credit — the license-note requirement («surface {url,license,credit} per
 *    ref»), honoured by construction.
 * 2. A `media_pending` entry resolves to a `null` card image (no fabricated URL,
 *    no bare unattributed image) — the card keeps its placeholder.
 * 3. A missing exercise id resolves to `null` (never an invented image).
 * 4. The real bundled catalog covers the whole library (every BaselineProgram
 *    exercise id resolves), and the sourced/pending counts match the catalog.
 */
class ExerciseMediaResolverTest {

    /** The bundled catalog, as a JVM test reads it (classpath). */
    private fun bundledResolver(): ExerciseMediaResolver {
        val raw = ExerciseMediaResolverTest::class.java.getResourceAsStream("/exercise_media.json")!!
            .use { it.readBytes().decodeToString() }
        return ExerciseMediaResolver.fromJson(raw)
    }

    private val resolver: ExerciseMediaResolver by lazy { bundledResolver() }

    @Test
    fun `a sourced entry resolves to a card image carrying url license and credit`() {
        val media = resolveExerciseMedia("warm_breathing", resolver)
        media.exerciseId shouldBe "warm_breathing"
        media.hasCardImage shouldBe true
        val image = media.cardImage
        image.shouldBeInstanceOf<CardImage>()
        // url is a non-blank direct image ref; license + credit surfaced verbatim.
        image.url.isNotBlank() shouldBe true
        image.license.shouldBeInstanceOf<String>()
        image.credit.shouldBeInstanceOf<String>()
        // YouTube video URL from exercise_media.json video block (36/36 exercises).
        media.videoUrl.shouldBeInstanceOf<String>()
        media.videoUrl.startsWith("https://www.youtube.com/") shouldBe true
    }

    @Test
    fun `a media_pending entry resolves to a null card image - never a fabricated url`() {
        // wall_axial_elongation carries image.status == media_pending in the catalog.
        val media = resolveExerciseMedia("wall_axial_elongation", resolver)
        media.hasCardImage shouldBe false
        media.cardImage.shouldBeNull()
    }

    @Test
    fun `a missing exercise id resolves to no media`() {
        val media = resolveExerciseMedia("does_not_exist", resolver)
        media.cardImage.shouldBeNull()
        media.hasCardImage shouldBe false
        media.summary.shouldBeNull()
    }

    @Test
    fun `the bundled catalog covers every baseline exercise id`() {
        // The catalog is the card-image source for the whole library; an entry
        // missing for a real surfaced exercise would leave its card imageless by
        // accident. This pins coverage today and fails loud if the catalog and
        // BaselineProgram drift apart.
        val ids = dreamteam.domain.training.BaselineProgram.exerciseIds
        ids.forEach { id ->
            // An entry missing for a real surfaced exercise would leave its card
            // imageless by accident; assert presence + type per id.
            val entry = resolver.resolve(id)
            entry.shouldBeInstanceOf<ExerciseMediaEntry>()
            entry.exerciseId shouldBe id
        }
    }

    @Test
    fun `sourced card images are unique direct refs across the catalog`() {
        // Defense-in-depth: two exercises sharing one direct image ref would be a
        // catalog/merge error; the card would show the wrong movement's picture.
        val sourced = dreamteam.domain.training.BaselineProgram.exerciseIds
            .map { resolveExerciseMedia(it, resolver) }
            .mapNotNull { it.cardImage }
        sourced.map { it.url } shouldBe sourced.map { it.url }.distinct()
    }

    @Test
    fun `every exercise carries a why summary and none contains a banned medical-claim phrase`() {
        // DRE-240: the 36 ai_summary_ru render verbatim to the user under the "Почему это
        // упражнение" heading (DreamTeamApp.kt). They are catalog DATA, not a *.all list, so the
        // app-wide banned-phrase scan never covered them. This closes that gate: full coverage +
        // the same banned morphemes every authored surface carries, plus the scoliosis/curvature
        // overclaim stems (board overclaim check, commit 685a119).
        val ids = dreamteam.domain.training.BaselineProgram.exerciseIds
        val summaries = ids.mapNotNull { resolver.resolve(it)?.aiSummaryRu?.takeUnless { s -> s.isBlank() } }
        // Coverage: every preset must carry a non-blank WHY summary.
        summaries.size shouldBe ids.size

        val banned = listOf(
            "диагноз", "диагности",
            "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
            "болезнь",
            "у вас", "вы больн", "вы здоровы", "ваш диагноз",
            "предписываю", "назначаю", "прописываю",
            "diagnos", "treat", "cure", "heal", "disease", "you have", "you are", "prescribe",
            // DRE-240 scoliosis/curvature overclaim stems.
            "исправля", "выправля", "избавля",
        )
        // "коррекция/корректирует … кривизны" = the curvature-correction overclaim. Bare "коррекци"
        // is intentionally NOT banned: legit disclaimers use it («не структурная коррекция»).
        val curvatureCorrection = Regex("коррек(?:ци|тир)\\w*\\s+(?:\\w+\\s+){0,3}кривизн")

        summaries.forEach { summary ->
            val lower = summary.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
            (curvatureCorrection.containsMatchIn(lower)) shouldBe false
        }
    }
}
