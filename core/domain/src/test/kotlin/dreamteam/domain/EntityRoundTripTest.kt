package dreamteam.domain

import dreamteam.domain.evidence.EvidenceSource
import dreamteam.domain.exercise.Exercise
import dreamteam.domain.nutrition.CalibrationRule
import dreamteam.domain.nutrition.NutritionTarget
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.profile.Anthropometrics
import dreamteam.domain.profile.SexForEquations
import dreamteam.domain.safety.RedFlag
import dreamteam.domain.safety.Recommendation
import dreamteam.domain.safety.RuleTrigger
import dreamteam.domain.safety.SafetyRule
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.SafetyVerdict
import dreamteam.domain.symptom.Symptom
import dreamteam.domain.training.ExerciseAssignment
import dreamteam.domain.training.PlanSession
import dreamteam.domain.training.PlanWeek
import dreamteam.domain.training.TrainingPlan
import dreamteam.domain.user.MedicalContext
import dreamteam.domain.user.User
import dreamteam.domain.user.UserPreferences
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test

/**
 * DRE-6 smoke test: round-trips (write + read) every core entity through its
 * persistence representation (kotlinx.serialization JSON — the storage format
 * behind the repository ports, ADR 0001). If any entity fails to survive a
 * serialize → deserialize cycle, the persistence schema is broken.
 *
 * The domain-layer round-trip proves the *schema*; the server-layer
 * [RepositoryLayerTest] proves feature code reaches storage only through the
 * port interfaces.
 */
class EntityRoundTripTest {
    private val json = Json { encodeDefaults = true }

    private inline fun <reified T> roundTrip(value: T): T {
        val s = serializer<T>()
        return json.decodeFromString(s, json.encodeToString(s, value))
    }

    @Test
    fun `User round-trips`() {
        val user = User(
            id = "user-1",
            anthropometrics = Anthropometrics(SexForEquations.MALE, 28, 188.0, 83.2, 21.2),
            equipment = listOf("dumbbells", "yoga block"),
            preferences = UserPreferences(3, 2, 60, "calisthenics and dumbbells"),
            medicalContext = MedicalContext(
                condition = "S-shaped scoliosis, user reports grade 2-3",
                scoliosisReported = true,
                currentCurveDataAvailable = false,
                clinicianCurveSpecificPlanAvailable = false,
                safetyGateStatus = "No urgent red flags reported; this is not medical clearance.",
            ),
        )
        roundTrip(user) shouldBe user
    }

    @Test
    fun `EvidenceSource round-trips`() {
        val source = EvidenceSource(
            id = "ACSM-RT-2026",
            domain = "resistance_training",
            citation = "Currier BS et al. ACSM Position Stand. Med Sci Sports Exerc. 2026.",
            design = "overview of 137 systematic reviews",
            keyFinding = "Progressive RT improves strength, hypertrophy and function.",
            application = "Three full-body sessions, progressive overload.",
            limitations = "Healthy-adult evidence; not scoliosis-specific.",
            pmid = "41843416",
            doi = "10.1249/MSS.0000000000003897",
            url = "https://pubmed.ncbi.nlm.nih.gov/41843416/",
            evidenceLevel = "high",
        )
        roundTrip(source) shouldBe source
    }

    @Test
    fun `Exercise round-trips and carries evidence`() {
        val exercise = Exercise(
            id = "split_squat",
            name = "Split squat",
            category = "knee_dominant",
            equipment = "dumbbells, yoga block",
            defaultSets = 3,
            repScheme = "8-15/side",
            rir = 2,
            instructions = "Equal volume both sides.",
            progression = "Reps -> weight -> pause.",
            regression = "Shorter range.",
            scoliosisRule = "Do not force visual symmetry.",
            evidenceRefs = listOf("ACSM-RT-2026"),
        )
        val back = roundTrip(exercise)
        back shouldBe exercise
        back.evidenceRefs shouldBe listOf("ACSM-RT-2026") // evidence linkage survives
    }

    @Test
    fun `NutritionTarget and CalibrationRule round-trip`() {
        val target = NutritionTarget(
            userId = "user-1",
            targetKcal = 2300,
            proteinG = 170,
            fatG = 75,
            carbohydrateG = 236,
            evidenceRefs = listOf("ENERGY-ESTIMATION"),
            recordedOn = "2026-07-21",
        )
        val calibration = CalibrationRule(
            minimumDaysBeforeAdjustment = 14,
            targetLossFractionPerWeek = listOf(0.002, 0.006),
            lowLossThresholdFractionPerWeek = 0.0015,
            highLossThresholdFractionPerWeek = 0.0075,
            minimumAdherence = 0.8,
            adjustmentKcalTooSlow = -100,
            adjustmentKcalTooFastOrRecoveryDeclines = 100,
        )
        roundTrip(target) shouldBe target
        roundTrip(calibration) shouldBe calibration
    }

    @Test
    fun `Symptom round-trips`() {
        val symptom = Symptom(
            id = "symp-1",
            userId = "user-1",
            recordedOn = "2026-07-21",
            source = "user self-report",
            currentSymptoms = listOf("left lumbar/hip tension"),
            context = "Stress-related episodic increase.",
            interpretation = "General gate may stay open; not medical clearance.",
        )
        roundTrip(symptom) shouldBe symptom
    }

    @Test
    fun `ProgressEntry round-trips`() {
        val entry = ProgressEntry(
            id = "prog-1",
            userId = "user-1",
            recordedOn = "2026-07-21",
            weightKg = 83.2,
            bodyFatPercent = 21.2,
            waistCm = null,
            bmrKcal = 1872,
            maintenanceKcalLow = 2560,
            maintenanceKcalHigh = 2750,
        )
        roundTrip(entry) shouldBe entry
    }

    @Test
    fun `SafetyRule round-trips with a sealed trigger`() {
        val rule = SafetyRule(
            id = "block_on_red_flag",
            description = "Any red flag blocks guidance; escalate.",
            trigger = RuleTrigger.RedFlagPresent(RedFlag.NIGHT_PAIN),
            decision = SafetyRule.Decision.BLOCK,
            evidenceRefs = listOf("SAFETY-RED-FLAGS"),
        )
        roundTrip(rule) shouldBe rule
    }

    @Test
    fun `TrainingPlan round-trips with per-assignment evidence`() {
        val plan = TrainingPlan(
            id = "plan-1",
            userId = "user-1",
            name = "12-week recomposition PoC",
            createdAt = "2026-07-21",
            weeks = listOf(
                PlanWeek(
                    weekNumber = 1,
                    phase = "re-entry",
                    setsMain = 2,
                    rir = 3,
                    volumeFactor = 0.7,
                    sessions = listOf(
                        PlanSession(
                            id = "strength_A",
                            day = "Monday",
                            label = "Strength A",
                            assignments = listOf(
                                ExerciseAssignment("split_squat", 2, "8-15/side", 2, listOf("ACSM-RT-2026")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val back = roundTrip(plan)
        back shouldBe plan
        // Evidence linkage survives at the plan-item level.
        back.weeks[0].sessions[0].assignments[0].evidenceRefs shouldBe listOf("ACSM-RT-2026")
    }

    @Test
    fun `SafetyVerdict round-trips through both variants`() {
        roundTrip(SafetyVerdict.Allow as SafetyVerdict) shouldBe SafetyVerdict.Allow
        roundTrip(SafetyVerdict.Block("no", listOf("r1")) as SafetyVerdict) shouldBe
            SafetyVerdict.Block("no", listOf("r1"))
    }
}
