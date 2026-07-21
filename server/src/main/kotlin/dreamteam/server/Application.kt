package dreamteam.server

import dreamteam.domain.nutrition.NutritionTarget
import dreamteam.domain.safety.MedicalSafety
import dreamteam.domain.safety.SafetyEvaluation
import dreamteam.domain.safety.SafetyGate
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.ScreeningContext
import dreamteam.domain.safety.StructuralSafetyRules
import dreamteam.domain.training.DeterministicPlanGenerator
import dreamteam.domain.training.GeneratedPlan
import dreamteam.domain.training.TrainingPlan
import dreamteam.server.persistence.EncryptionKey
import dreamteam.server.persistence.EncryptionKeys
import dreamteam.server.persistence.SqliteRepositories
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module).start(wait = true)
}

/**
 * The durable repository wiring for one server process: one encrypted SQLite
 * file behind the eight repository ports (ADR 0003 / DRE-16). Default DB path
 * comes from `DREAMTEAM_DB` (or `dreamteam.db` in the working dir); the AES-256
 * at-rest key is injected from the `DREAMTEAM_DB_KEY` env var (base64, 32
 * bytes — fatal if missing/wrong, never a silent plaintext fallback). Both are
 * deployment secrets, never committed.
 */
class ServerDeps(jdbcUrl: String, key: EncryptionKey) : AutoCloseable {
    private val repos = SqliteRepositories.open(jdbcUrl, key)
    val users = repos.users
    val plans = repos.plans
    val progress = repos.progress
    val symptoms = repos.symptoms
    val nutrition = repos.nutrition
    val exercises = repos.exercises
    val evidence = repos.evidence
    val rules = repos.safetyRules
    override fun close() = repos.close()
}

/** Resolves the DB path: explicit arg > env > local default. */
fun resolveDbPath(): String = System.getenv("DREAMTEAM_DB")?.takeIf { it.isNotBlank() } ?: "dreamteam.db"

/** Resolves the SQLite JDBC url for [ServerDeps]. */
fun resolveJdbcUrl(): String = "jdbc:sqlite:${resolveDbPath()}"

/** Resolves the AES-256 at-rest key (base64, 32 bytes) from the deployment env. */
fun resolveEncryptionKey(): EncryptionKey = EncryptionKeys.fromBase64Env("DREAMTEAM_DB_KEY")

fun Application.module(jdbcUrl: String = resolveJdbcUrl(), key: EncryptionKey = resolveEncryptionKey()) {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true; ignoreUnknownKeys = true })
    }
    val deps = ServerDeps(jdbcUrl, key)

    routing {
        // Infra liveness probe — no medical data, no claims.
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "dreamteam-server"))
        }

        // ---- /v1 surface (ADR 0001) ---------------------------------------
        route("/v1") {
            // Deterministic, pre-LLM. The verdict is binding: no client can
            // override it. Implements invariants #1 (safety = code) and #3
            // (side-specific lock). See specs/SDD.md §2.4.
            post("/safety/evaluate") {
                val safety = call.receive<MedicalSafety>()
                call.respond(SafetyGate.evaluate(safety))
            }

            // POST /v1/plans/generate — deterministic baseline plan produced
            // EXCLUSIVELY via SafetyGuardedGateway.surface() (M2-A done-when #1).
            // The deterministic path runs FIRST: pre-LLM red-flag gate, then the
            // per-recommendation structural gate. No LLM, no client can skip it.
            // A blocked candidate (red flag or unlisted id) returns 409, never a
            // 200 with a hole in the plan. The vetted plan + nutrition are
            // persisted durably (ADR 0003).
            post("/plans/generate") {
                val request = call.receive<PlanGenerateRequest>()
                val medical = request.medicalSafety
                val userId = request.userId.ifBlank { "seed-user" }
                val safetyEval = SafetyGate.evaluate(medical)

                // Pre-LLM red-flag gate: a reported red flag closes generation.
                if (!safetyEval.allowTrainingGeneration) {
                    call.respond(
                        io.ktor.http.HttpStatusCode.Conflict,
                        BlockedResponse(status = "blocked_red_flag", safety = safetyEval),
                    )
                    return@post
                }

                val context = ScreeningContext(
                    redFlags = emptySet(), // gate already passed => none reported
                    sideSpecificLockEngaged = !safetyEval.allowSideSpecificContent,
                    allowedExerciseIds = dreamteam.domain.training.BaselineProgram.exerciseIds,
                    allowedEvidenceIds = dreamteam.domain.training.BaselineProgram.evidenceIds,
                    clinicianCurveSpecificPlanAvailable = medical.clinicianCurveSpecificPlanAvailable,
                    conditionFlags = if (medical.scoliosisReported) setOf("scoliosis_flagged") else emptySet(),
                )
                val gateway = SafetyGuardedGateway(context, StructuralSafetyRules.all)
                val generated = DeterministicPlanGenerator(gateway).generate(
                    userId = userId,
                    createdAt = LocalDate.now().toString(),
                )

                when (generated) {
                    is GeneratedPlan.Ok -> {
                        deps.plans.save(generated.plan)
                        deps.nutrition.save(generated.nutrition)
                        call.respond(
                            PlanResponse(
                                status = "ok",
                                safety = safetyEval,
                                plan = generated.plan,
                                nutrition = generated.nutrition,
                            ),
                        )
                    }
                    is GeneratedPlan.Blocked -> call.respond(
                        io.ktor.http.HttpStatusCode.Conflict,
                        BlockedResponse(
                            status = "blocked",
                            safety = safetyEval,
                            blockedExerciseIds = generated.blockedExerciseIds.distinct(),
                            ruleIds = generated.ruleIds,
                        ),
                    )
                }
            }
        }
    }
}

@Serializable
data class PlanGenerateRequest(
    @SerialName("user_id") val userId: String = "",
    @SerialName("medical_safety") val medicalSafety: MedicalSafety = MedicalSafety(),
)

@Serializable
data class PlanResponse(
    val status: String,
    val safety: SafetyEvaluation,
    val plan: TrainingPlan,
    val nutrition: NutritionTarget,
)

@Serializable
data class BlockedResponse(
    val status: String,
    val safety: SafetyEvaluation,
    @SerialName("blocked_exercise_ids") val blockedExerciseIds: List<String> = emptyList(),
    @SerialName("rule_ids") val ruleIds: List<String> = emptyList(),
)
