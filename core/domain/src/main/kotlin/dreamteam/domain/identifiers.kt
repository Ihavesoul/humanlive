package dreamteam.domain

/**
 * Stable, human-readable entity identifiers. Values match the `id` fields in the
 * PoC's JSON files under data/ (the inviolable source of truth).
 *
 * These are [typealias]s, not type-safe wrappers: they document intent at zero
 * runtime cost and stay serialization-friendly (matching the PoC's string ids),
 * but they do NOT prevent cross-kind mixing at compile time. Cross-references
 * are validated at runtime by the repository layer's allowlist checks
 * (e.g. [dreamteam.domain.persistence.EvidenceSourceRepository.contains]).
 */
typealias EvidenceId = String
typealias ExerciseId = String
typealias UserId = String
typealias PlanId = String
typealias NutritionPlanId = String
typealias RuleId = String
typealias SymptomId = String
typealias ProgressId = String
