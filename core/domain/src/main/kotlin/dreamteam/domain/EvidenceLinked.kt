package dreamteam.domain

/**
 * Contract for anything that must carry traceable evidence before it reaches the
 * user. Implemented by [dreamteam.domain.exercise.Exercise], plan assignments,
 * nutrition targets, and safety rules.
 *
 * An empty [evidenceRefs] means "blocked until sourced", never "safe to ship":
 * the plan/safety layers refuse to surface anything with no resolvable source
 * (DRE-6 done-when: every Exercise / plan item can reference an EvidenceSource).
 */
interface EvidenceLinked {
    val evidenceRefs: List<EvidenceId>
}
