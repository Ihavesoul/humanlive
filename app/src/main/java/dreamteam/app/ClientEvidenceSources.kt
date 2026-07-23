package dreamteam.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp

/**
 * M6-D (stretch) ([DRE-66](/DRE/issues/DRE-66)): the read-only "evidence
 * sources" surface — the FULL allowlisted catalog the app draws on, each entry
 * rendered as citation + `evidenceLevel` + `keyFinding`, NO interpretation.
 * Pure transparency: M6-A/B/C make the "evidence-linked" promise visible
 * per-plan / per-block; this makes the whole allowlist inspectable so a user can
 * see exactly what evidence backs the app.
 *
 * Reuses the SAME M6-A resolver ([EvidenceResolver]) + M6-B render path
 * ([resolveCitations]) — one catalog, one render, no second source of truth, no
 * network. Deterministic, read-only. `evidenceLevel` is the catalog's
 * controlled-vocabulary label rendered VERBATIM (not an appraisal); an explicit
 * transparency-not-recommendation disclaimer is always present.
 *
 * Two layers keep it pure + JVM-testable (the [nutritionPlanView] pattern):
 *  - [evidenceSourcesView] — pure over a resolver; a JVM unit test pins one row
 *    per catalog entry (nothing dropped, nothing invented) + the no-claim
 *    disclaimer.
 *  - [EvidenceSourcesScreen] — the Compose surface; Android I/O only at the
 *    edge ([loadEvidenceResolver], decoded once at the Compose root). It is
 *    wired into [DreamTeamApp]'s nav (`Screen.EvidenceSources`, reachable as a
 *    one-tap entry on the Today landing) so the full catalog is inspectable.
 */

/**
 * What [EvidenceSourcesScreen] shows: one READABLE row per catalog entry
 * (citation + `evidenceLevel` + `keyFinding` via the shared [resolveCitations]
 * path), plus an always-present transparency-not-recommendation disclaimer.
 */
internal data class EvidenceSourcesView(
    val rows: List<ResolvedCitation>,
    val disclaimer: String,
)

/**
 * Render the FULL catalog as readable rows — every entry the app can cite,
 * verbatim (citation + `evidenceLevel` + `keyFinding`), no interpretation. Pure
 * (no Android, no I/O) so a JVM test pins: one resolved row per catalog entry,
 * the authored disclaimer carries no banned medical-claim phrase, and the row
 * count == catalog size (nothing dropped, nothing invented).
 *
 * Reuses [resolveCitations] over [EvidenceResolver.allSources] ids — the same
 * render path the plan/block views use, so the catalog screen cannot drift from
 * how a citation appears in-plan. Order is the catalog's decoded order
 * (deterministic).
 */
internal fun evidenceSourcesView(resolver: EvidenceResolver): EvidenceSourcesView =
    EvidenceSourcesView(
        rows = resolveCitations(resolver.allSources().map { it.id }, resolver),
        disclaimer = EvidenceSourcesStrings.DISCLAIMER,
    )

/**
 * The authored strings the evidence-sources screen renders. Gathered as one
 * list ([all]) so a JVM test can snapshot them against the banned medical-claim
 * phrase list (mirrors [TodayStrings] / [HistoryStrings]). Transparency framing
 * only: no diagnosis, no quality appraisal, no "recommendation". The verbatim
 * catalog citation ROWS are deliberately NOT in [all] — they legitimately carry
 * study vocabulary ("health", "treatment") inside titles, so the crude-substring
 * scan would false-positive on vetted evidence (the M6-B design call); the
 * catalog-side claim guard is rendering them verbatim + this disclaimer.
 */
internal object EvidenceSourcesStrings {
    const val TITLE = "Источники доказательной базы"
    /** Transparency framing — NOT a quality appraisal or a recommendation. */
    const val DISCLAIMER =
        "Это список источников, на которые опирается приложение, для прозрачности — " +
            "не оценка качества и не рекомендация. Уровень — метка из каталога, а не оценка исследования."
    const val BACK = "Назад"

    val all: List<String> = listOf(TITLE, DISCLAIMER, BACK)
}

/**
 * The read-only evidence-sources screen — renders [evidenceSourcesView] (pure)
 * so there is no logic in the tree; Android I/O only at the edge. Framed as pure
 * transparency: each catalog entry as citation + `evidenceLevel` + `keyFinding`,
 * no interpretation, with the support/transparency disclaimer. Nav wiring is
 * deferred (see file KDoc / DRE-70).
 */
@Composable
internal fun EvidenceSourcesScreen(modifier: Modifier, resolver: EvidenceResolver, onBack: () -> Unit) {
    val view = remember { evidenceSourcesView(resolver) }
    LazyColumn(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(EvidenceSourcesStrings.TITLE, fontWeight = FontWeight.Bold) }
        item { Text(EvidenceSourcesStrings.DISCLAIMER, fontWeight = FontWeight.Light, fontStyle = FontStyle.Italic) }
        items(view.rows) { row ->
            Text("• ${row.line}", fontWeight = FontWeight.Light)
        }
        item { OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(EvidenceSourcesStrings.BACK) } }
    }
}
