package dreamteam.app

import android.content.res.AssetManager
import dreamteam.domain.EvidenceId
import dreamteam.domain.evidence.EvidenceSource
import kotlinx.serialization.json.Json

/**
 * M6-A ([DRE-67](/DRE/issues/DRE-67)): the client-side evidence resolver — the
 * offline-first, read-only bridge from a surfaced [EvidenceSource] id to its
 * catalog entry. Every surfaced [dreamteam.domain.EvidenceLinked] entity
 * (training [dreamteam.domain.training.ExerciseAssignment],
 * [dreamteam.domain.nutrition.NutritionPlan], …) carries `evidenceRefs`; this
 * resolves them against the bundled `data/evidence_catalog.json` (ADR 0001
 * durable source of truth) so M6-B/M6-C can render readable citations — no
 * network, no invented citation (an unresolvable id yields `null` and the caller
 * renders a blocked-until-sourced placeholder, honoring EvidenceLinked).
 *
 * Mirrors the server's [dreamteam.server.plan.BaselinePlan]:
 * `evidenceCatalog.associateBy { it.id }` + `PlanCitation(id, keyFinding…)`.
 * Two layers keep resolution pure + JVM-testable (the
 * [nutritionPlanView] / [adaptationNote] pattern):
 *  - [EvidenceResolver] — pure over a decoded catalog; a JVM unit test pins the
 *    three M6-A guarantees (0 dangling, deterministic, ghost→null).
 *  - [loadEvidenceResolver] — the single Android-I/O point: reads the bundled
 *    asset. Pure code never touches an [AssetManager]/[android.content.Context].
 *
 * Read-only: this slice only reads the catalog (no writes). It does NOT implement
 * the write-bearing [dreamteam.domain.persistence.EvidenceSourceRepository] port
 * — a read-only bundled catalog has no `save`; adding one would be an unrequested
 * write path. A later slice can adapt if a mutable client cache is wanted.
 */

/** The exact decode the server uses ([BaselinePlan]: `ignoreUnknownKeys`). */
internal val evidenceJson: Json = Json { ignoreUnknownKeys = true }

/**
 * A pure id→[EvidenceSource] view over a decoded catalog. Built from bytes (no
 * Android dependency), so a JVM unit test pins the guarantees without a device.
 * Mirrors the server's `evidenceCatalog.associateBy { it.id }`. Never invents a
 * citation: a missing id resolves to `null`.
 */
internal class EvidenceResolver(catalog: List<EvidenceSource>) {
    private val byId: Map<EvidenceId, EvidenceSource> = catalog.associateBy { it.id }

    /** Resolve an id to its catalog source, or `null` for a dangling/ghost id. */
    fun resolveEvidence(id: EvidenceId): EvidenceSource? = byId[id]

    /**
     * The full catalog in decoded order (M6-D / [DRE-66](/DRE/issues/DRE-66)): a
     * read-only enumeration so the evidence-sources screen can render the whole
     * allowlist the app draws on. `associateBy` keeps a LinkedHashMap, so order
     * is the catalog's insertion order (deterministic). No second source of
     * truth — the same decoded list [resolveEvidence] resolves against.
     */
    fun allSources(): List<EvidenceSource> = byId.values.toList()

    companion object {
        /** Decode + build the resolver from raw catalog JSON (the exact server decode). */
        fun fromJson(rawJson: String): EvidenceResolver =
            EvidenceResolver(evidenceJson.decodeFromString(rawJson))
    }
}

/**
 * The single Android-I/O point: decode the bundled `evidence_catalog.json` asset
 * into a pure [EvidenceResolver]. Offline-first — no network; the catalog ships
 * in the APK (app/build.gradle.kts adds repo-root `data/` as an assets source —
 * one copy of the data, no drift, same as the server's classpath resource).
 */
internal fun loadEvidenceResolver(assets: AssetManager): EvidenceResolver =
    EvidenceResolver.fromJson(assets.open("evidence_catalog.json").use { it.readBytes().decodeToString() })

/**
 * M6-B ([DRE-68](/DRE/issues/DRE-68)): the pure render of a surfaced
 * [dreamteam.domain.EvidenceLinked] ref against the catalog — a readable
 * citation (author/year + keyFinding + evidenceLevel) for a resolved id, or a
 * blocked-until-sourced placeholder for a ghost id. Never an invented citation
 * (honors EvidenceLinked). Shared by the nutrition + training views so both
 * surfaces use ONE render path; pure + JVM-testable (the
 * [nutritionPlanView] pattern), Android I/O only at [loadEvidenceResolver].
 *
 * [line] carries the user-facing text so the Compose surface only does
 * `Text(line)` — no formatting logic in the tree. [evidenceLevel] is the
 * catalog's controlled-vocabulary value rendered VERBATIM: a label, NOT an
 * appraisal of study quality (this slice does not vet studies; the Evidence &
 * Research Analyst owns the catalog). Rendering it raw keeps the label honest
 * and avoids a level→translation map that could drift from the catalog.
 */
internal data class ResolvedCitation(
    val id: EvidenceId,
    /** A catalog entry was found — `false` means [line] is the placeholder. */
    val resolved: Boolean,
    val line: String,
)

/**
 * The blocked-until-sourced placeholder for an id that resolves to no catalog
 * entry. Honors EvidenceLinked: a ref with no source surfaces a transparency
 * line, never a fabricated citation. In practice M6-A's 0-dangling invariant
 * means surfaced refs always resolve; this is the defensive contract.
 */
internal const val EVIDENCE_NOT_SOURCED =
    "источник не каталогизирован — рекомендация без подтверждённой ссылки"

/**
 * Resolve each ref to a readable citation, or to [EVIDENCE_NOT_SOURCED] for a
 * ghost id. Pure; mirrors the M6-A `resolveEvidence(id) == null` → placeholder
 * contract. One entry per ref, in order, so the surface renders one line each.
 */
internal fun resolveCitations(
    refs: List<EvidenceId>,
    resolver: EvidenceResolver,
): List<ResolvedCitation> = refs.map { id ->
    val src = resolver.resolveEvidence(id)
    if (src != null) {
        ResolvedCitation(
            id = id,
            resolved = true,
            line = "${src.citation} (уровень: ${src.evidenceLevel}) — ${src.keyFinding}",
        )
    } else {
        ResolvedCitation(id = id, resolved = false, line = EVIDENCE_NOT_SOURCED)
    }
}
