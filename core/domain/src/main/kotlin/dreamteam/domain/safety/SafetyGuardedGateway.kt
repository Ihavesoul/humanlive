package dreamteam.domain.safety

/**
 * The single, compile-time-enforced integration point between a *candidate*
 * recommendation and anything that reaches the user. This is the "safety gate =
 * code, not model" invariant (ADR 0001 §1) made structural: a plan-generation
 * path cannot bypass it because [SurfacedPlan] — the only type a render layer
 * may consume — has no public constructor. The sole producer is [surface].
 *
 * **Block-by-default.** The gateway evaluates only [RuleStatus.ACTIVE] rules and
 * the underlying engine blocks when the active ruleset is empty. Consequences:
 *  - An *unprovisioned* gateway (no active rules) blocks every recommendation.
 *  - A candidate is surfaced only if no active rule blocks it *and* at least one
 *    active rule is registered (the Safety Reviewer's sign-off).
 *  - Unsafe output therefore cannot reach the user even before any real rules
 *    exist (DRE-7 done-when).
 *
 * **All-or-nothing surfacing.** [surface] returns a fully-vetted plan or none:
 * if any candidate is blocked, *nothing* is surfaced. A partial plan with a
 * silently-dropped contraindicated exercise is a footgun we refuse to ship; the
 * blocked items are reported for transparency/logging, never rendered as guidance.
 *
 * This type enforces *safety*; it does not author medical rules. Clinical
 * content lives in the active [SafetyRule]s the Safety Reviewer supplies
 * (see [ContraindicationStubs] for the documented DRAFT slots).
 */
class SafetyGuardedGateway(
    context: ScreeningContext,
    rules: List<SafetyRule>,
) {
    private val context: ScreeningContext = context
    private val activeRules: List<SafetyRule> = rules.filter { it.status == RuleStatus.ACTIVE }

    /** Vets a single recommendation. Binding; no override. */
    fun vet(recommendation: Recommendation): SafetyVerdict =
        SafetyRuleEngine.evaluate(recommendation, context, activeRules)

    /**
     * The only way candidate recommendations become a [SurfacedPlan]. Returns
     * [SurfacedPlan.Ok] only when *every* candidate is allowed; otherwise
     * [SurfacedPlan.Blocked] (surfaced is empty).
     */
    fun surface(candidates: List<Recommendation>): SurfacedPlan {
        val blocked = mutableListOf<SurfacedPlan.BlockedItem>()
        for (rec in candidates) {
            val verdict = vet(rec)
            if (verdict is SafetyVerdict.Block) {
                blocked += SurfacedPlan.BlockedItem(rec, verdict)
            }
        }
        return if (blocked.isEmpty()) {
            SurfacedPlan.Ok(candidates)
        } else {
            SurfacedPlan.Blocked(blocked)
        }
    }
    // ponytail: Ok/Blocked constructors are `internal`, not `private`, only
    // because this class (not a nested friend) must mint them. `internal` still
    // makes them invisible to the :server/:app Gradle modules where plan-gen
    // code lives — that is the real bypass threat. A private ctor would force
    // awkward factories for no extra cross-module safety.

    /** True when at least one active rule is registered (i.e. the gate is provisioned). */
    val isProvisioned: Boolean get() = activeRules.isNotEmpty()
}

/**
 * The result of vetting a candidate plan through [SafetyGuardedGateway]. The
 * only legal producer is [SafetyGuardedGateway.surface]: both implementations
 * have `internal` constructors (plus `@ConsistentCopyVisibility`, so `copy()` is
 * internal too), so no code in another Gradle module (`:server`, `:app` — where
 * plan-generation code lives) can construct a [SurfacedPlan]. A render layer
 * depends on this type and can pattern-match it, but cannot fabricate a "safe"
 * plan.
 *
 * [surfaced] is always the list that may reach the user — empty whenever
 * anything was blocked.
 */
sealed interface SurfacedPlan {
    val surfaced: List<Recommendation>

    /** Every candidate passed the gate. [surfaced] is the full, vetted plan. */
    @ConsistentCopyVisibility
    data class Ok internal constructor(
        override val surfaced: List<Recommendation>,
    ) : SurfacedPlan

    /** At least one candidate was blocked; [surfaced] is empty — nothing reaches the user. */
    @ConsistentCopyVisibility
    data class Blocked internal constructor(
        val items: List<BlockedItem>,
    ) : SurfacedPlan {
        override val surfaced: List<Recommendation> get() = emptyList()
    }

    /** A candidate that was blocked, paired with its binding verdict (for logging/audit). */
    data class BlockedItem(val recommendation: Recommendation, val verdict: SafetyVerdict.Block)
}
