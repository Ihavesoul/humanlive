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
