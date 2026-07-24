package dreamteam.domain.safety

import dreamteam.domain.training.BaselineProgram

/**
 * The single provisioned-[SafetyGuardedGateway] factory the plan routes and the
 * M8-C coach share: the same screening context (allowlists from the PoC baseline
 * + scoliosis_flagged derivation) and the same ACTIVE rules (structural
 * allowlists + contraindications, DRE-10/24). Extracted so the server's plan
 * routes ([dreamteam.server.Application]) and the coach
 * ([dreamteam.domain.coach.Coach]) cannot drift on the gate wiring — one
 * chokepoint, evaluated identically on both tiers.
 *
 * [safetyEval] is the *already-computed* [SafetyGate.evaluate] verdict; the
 * red-flag gate is upstream of this call (a caller reaching here passed it), so
 * `redFlags = emptySet()` — the side-specific lock is the remaining gate this
 * context carries. A flagged-scoliosis request proposing a heavy_axial_loading /
 * loaded_flexion_rotation movement is BLOCKED here regardless of caller.
 */
fun provisionedSafetyGateway(medical: MedicalSafety, safetyEval: SafetyEvaluation): SafetyGuardedGateway {
    val context = ScreeningContext(
        redFlags = emptySet(),
        sideSpecificLockEngaged = !safetyEval.allowSideSpecificContent,
        allowedExerciseIds = BaselineProgram.exerciseIds,
        allowedEvidenceIds = BaselineProgram.evidenceIds,
        clinicianCurveSpecificPlanAvailable = medical.clinicianCurveSpecificPlanAvailable,
        conditionFlags = if (medical.scoliosisReported) setOf("scoliosis_flagged") else emptySet(),
    )
    return SafetyGuardedGateway(context, StructuralSafetyRules.all + ContraindicationStubs.all)
}
