package dreamteam.domain.profile

/**
 * Sex used by sex-specific energy equations. Kept as the PoC's
 * "sex_for_equations" notion — it is a formula input, not identity.
 */
enum class SexForEquations { MALE, FEMALE }

/**
 * Body-composition + anthropometric inputs to the energy equations.
 *
 * Values are best-effort estimates (consumer BIA, self-reported); they drive a
 * *support* plan, never a diagnosis. Trend matters more than any single point.
 */
@kotlinx.serialization.Serializable
data class Anthropometrics(
    val sex: SexForEquations,
    val ageYears: Int,
    val heightCm: Double,
    val weightKg: Double,
    /** Consumer-BIA body fat %. Nullable: many equations degrade gracefully without it. */
    val bodyFatPercent: Double? = null,
)
