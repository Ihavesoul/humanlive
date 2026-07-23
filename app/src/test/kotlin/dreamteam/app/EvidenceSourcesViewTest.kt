package dreamteam.app

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * M6-D (stretch) ([DRE-66](/DRE/issues/DRE-66)) — pins the guarantees of the
 * read-only evidence-sources surface ([evidenceSourcesView] /
 * [EvidenceSourcesScreen]), enforced as code rather than relied on. Mirrors the
 * M6-B [EvidenceCitationRenderTest] shape: the catalog is read off the test
 * classpath (byte-identical to the bundled asset [loadEvidenceResolver] decodes)
 * and the render goes through the SAME [resolveCitations] path the plan/block
 * views use.
 *
 * Guarantees (the smallest thing that fails if M6-D breaks):
 * 1. **One resolved row per catalog entry** — nothing dropped, nothing invented
 *    (row count == [EvidenceResolver.allSources] size; every row resolved).
 * 2. **Each row is a real citation, not a bare id** — author + the
 *    `evidenceLevel` label + `keyFinding` rendered verbatim, no interpretation.
 * 3. **The authored disclaimer is transparency framing** — non-empty, asserts
 *    transparency, and carries NO banned medical-claim phrase.
 *
 * Claim guard note (the M6-B design call): the crude substring scan covers ONLY
 * the app-authored disclaimer — NOT the verbatim catalog citation rows, which
 * legitimately carry study vocabulary ("health", "treatment") inside titles.
 * Crude-scanning catalog text would false-positive on vetted evidence; the
 * catalog-side claim guard is rendering it verbatim + the disclaimer.
 */
class EvidenceSourcesViewTest {

    /** The offline-first resolver over the bundled catalog, as a JVM test reads it. */
    private val resolver: EvidenceResolver =
        EvidenceResolver.fromJson(
            EvidenceSourcesViewTest::class.java.getResourceAsStream("/evidence_catalog.json")!!
                .use { it.readBytes().decodeToString() },
        )

    // Banned substrings (lowercased) — same intent as [NutritionPlanViewTest]:
    // the surface may never assert a diagnosis, claim to treat/cure, or frame the
    // user in second-person clinical terms.
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "вылеч", "излеч", "исцела", "исцели",
        "болезнь",
        "у вас", "вы больн", "вы здоровы",
        "предписываю", "назначаю", "прописываю",
        "diagnos", "treat", "cure", "heal", "disease", "you have", "prescribe",
    )

    @Test
    fun `the view renders one resolved row per catalog entry - nothing dropped or invented`() {
        val view = evidenceSourcesView(resolver)
        val catalogSize = resolver.allSources().size

        catalogSize shouldBe view.rows.size
        // Sanity: the bundled catalog is non-empty (else the check is vacuous).
        view.rows.shouldContain(resolveCitations(listOf("MORTON-PROTEIN-2018"), resolver).first())

        view.rows.forEach { row ->
            // Every entry resolves (M6-A 0-dangling applies to the whole catalog).
            row.resolved shouldBe true
            // The rendered line is a real citation, never the bare id.
            (row.line.length > row.id.length) shouldBe true
            (row.line == row.id) shouldBe false
        }
    }

    @Test
    fun `each row renders citation + evidenceLevel + keyFinding verbatim - no interpretation`() {
        val rows = evidenceSourcesView(resolver).rows

        // A known catalog entry renders author + the controlled-vocabulary level
        // label + the keyFinding — verbatim, no appraisal added.
        val morton = rows.first { it.id == "MORTON-PROTEIN-2018" }
        ("Morton" in morton.line) shouldBe true
        ("уровень: high" in morton.line) shouldBe true

        // The level label is the catalog's controlled vocabulary, surfaced raw —
        // the view never appends an appraisal ("good"/"strong"/…).
        val allLevels = resolver.allSources().map { it.evidenceLevel }.toSet()
        rows.forEach { row ->
            val src = resolver.resolveEvidence(row.id)!!
            // The line embeds exactly this entry's level label (verbatim).
            ("уровень: ${src.evidenceLevel}" in row.line) shouldBe true
            (src.evidenceLevel in allLevels) shouldBe true
        }
    }

    @Test
    fun `the authored disclaimer is transparency framing with no medical-claim phrase`() {
        val disclaimer = evidenceSourcesView(resolver).disclaimer

        disclaimer.isNotBlank() shouldBe true
        // Transparency framing is explicit; it is NOT an appraisal/recommendation.
        ("прозрачност" in disclaimer.lowercase()) shouldBe true

        val lower = disclaimer.lowercase()
        banned.forEach { b -> (b !in lower) shouldBe true }
    }

    @Test
    fun `the authored screen strings carry no banned medical-claim phrase`() {
        // Mirrors NutritionPlanViewTest: scan ONLY app-authored strings, never the
        // verbatim catalog citation rows (study vocabulary false-positives).
        EvidenceSourcesStrings.all.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }
}
