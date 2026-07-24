package dreamteam.domain.safety

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The medical-safety subset of a workout request, mirroring the
 * `medical_safety` object in `data/schemas/workout_request.schema.json`.
 *
 * These are the *deterministic* inputs to the pre-LLM safety gate. They are
 * user/clinician-reported facts; this type holds no medical judgement of its
 * own. Field names match the schema so the wire shape is the single source of
 * truth.
 */
@Serializable
data class MedicalSafety(
    @SerialName("scoliosis_reported") val scoliosisReported: Boolean = false,
    /** Reported red flags, drawn from the schema's fixed enum. Empty = none reported. */
    @SerialName("red_flags") val redFlags: List<String> = emptyList(),
    @SerialName("current_curve_data_available") val currentCurveDataAvailable: Boolean = false,
    @SerialName("current_cobb_angles_deg") val currentCobbAnglesDeg: List<Double> = emptyList(),
    @SerialName("curve_classification") val curveClassification: String? = null,
    @SerialName("clinician_curve_specific_plan_available") val clinicianCurveSpecificPlanAvailable: Boolean = false,
    @SerialName("clinician_instructions") val clinicianInstructions: String? = null,
    @SerialName("current_symptoms") val currentSymptoms: List<String> = emptyList(),
)

/**
 * Deterministic safety-gate verdict. Matches the `/v1/safety/evaluate`
 * response in `specs/API_Contracts.md`.
 *
 * No field here is ever derived from an LLM. The gate runs pre-LLM and its
 * verdict cannot be overridden by any client (ADR 0001 invariant #1).
 */
@Serializable
data class SafetyEvaluation(
    @SerialName("red_flag_gate_passed") val redFlagGatePassed: Boolean,
    @SerialName("allow_training_generation") val allowTrainingGeneration: Boolean,
    @SerialName("allow_side_specific_content") val allowSideSpecificContent: Boolean,
    @SerialName("warnings") val warnings: List<String>,
)

/**
 * Deterministic, pre-LLM safety gate. This is the "safety = code, not model"
 * invariant made enforceable: the red-flag gate and the side-specific lock are
 * evaluated here, before any provider is called, and the result is binding.
 *
 * Rules encoded (sourced from existing PoC artefacts, not authored here):
 *  - **Red-flag gate** — any reported red flag closes the training gate. The
 *    flag set is the enum in `workout_request.schema.json`; see
 *    `prompts/red_flag_gate_prompt_ru.md` ("the blocking decision is made by
 *    code, not the LLM") and `specs/SDD.md` §2.4. This is deliberately
 *    conservative: a reported red flag routes to assessment, never to a plan.
 *  - **Side-specific lock** — curve-specific content (Cobb angle, wedge,
 *    directional breathing, unequal load) stays locked unless *current*
 *    clinician curve data is present (data available + classification +
 *    clinician plan). Historic x-ray photos never unlock it (ADR 0001 #3).
 *
 * Refinement of *which* red flags warrant urgent vs. routine assessment is a
 * Safety Reviewer decision; this gate treats every reported flag as gating.
 * The single [evaluate] entry point is the place to refine that, if ever.
 *
 * **DRE-100:** the [evaluate] overload that takes free-text notes derives
 * [RedFlag](s) via [NoteRedFlagScreening] and merges them into the medical
 * input before this primitive runs, so a note-derived flag is indistinguishable
 * from a structured one (block → route to assessment). Call that overload
 * whenever free-text notes are available; the 1-arg [evaluate] below stays the
 * pure primitive for callers with no note input (e.g. the single-exercise cue).
 *
 * This app does not diagnose. A blocked gate means "route to assessment", not
 * "you have condition X".
 */
object SafetyGate {

    fun evaluate(medical: MedicalSafety, notes: List<String>): SafetyEvaluation {
        val derived = NoteRedFlagScreening.derive(notes)
        if (derived.isEmpty()) return evaluate(medical)
        // ponytail: the gate only checks redFlags.isNotEmpty(), so merging derived
        // flag serial-names into the medical input is the single unmissable hook.
        val merged = medical.copy(redFlags = medical.redFlags + derived.map { it.serialName() })
        return evaluate(merged)
    }

    fun evaluate(safety: MedicalSafety): SafetyEvaluation {
        val redFlagGatePassed = safety.redFlags.isEmpty()
        val allowTrainingGeneration = redFlagGatePassed
        val hasCurrentClinicianCurveData =
            safety.currentCurveDataAvailable &&
                !safety.curveClassification.isNullOrBlank() &&
                safety.clinicianCurveSpecificPlanAvailable
        val allowSideSpecificContent = allowTrainingGeneration && hasCurrentClinicianCurveData

        val warnings = buildList {
            if (!redFlagGatePassed) {
                val first = safety.redFlags.first()
                add(
                    "Red flag reported ($first): training generation blocked. " +
                        "Seek medical assessment \u2014 this app does not diagnose.",
                )
            }
            if (redFlagGatePassed && !allowSideSpecificContent) {
                add(
                    "Curve-specific correction remains locked: requires current clinician " +
                        "curve data (Cobb angles + classification + clinician plan).",
                )
            }
        }
        return SafetyEvaluation(
            redFlagGatePassed = redFlagGatePassed,
            allowTrainingGeneration = allowTrainingGeneration,
            allowSideSpecificContent = allowSideSpecificContent,
            warnings = warnings,
        )
    }

    /** The wire string a [RedFlag] carries in `medical_safety.red_flags` — its `@SerialName`. */
    private fun RedFlag.serialName(): String = when (this) {
        RedFlag.PROGRESSIVE_LEG_WEAKNESS -> "progressive_leg_weakness"
        RedFlag.NUMBNESS_OR_SADDLE_ANAESTHESIA -> "numbness_or_saddle_anaesthesia"
        RedFlag.BOWEL_OR_BLADDER_DYSFUNCTION -> "bowel_or_bladder_dysfunction"
        RedFlag.NIGHT_PAIN -> "night_pain"
        RedFlag.UNINTENTIONAL_WEIGHT_LOSS -> "unintentional_weight_loss"
        RedFlag.FEVER -> "fever"
        RedFlag.RECENT_MAJOR_TRAUMA -> "recent_major_trauma"
        RedFlag.RAPID_NEUROLOGICAL_PROGRESSION -> "rapid_neurological_progression"
    }
}
