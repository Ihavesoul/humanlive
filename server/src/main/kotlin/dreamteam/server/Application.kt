package dreamteam.server

import dreamteam.domain.nutrition.NutritionTarget
import dreamteam.domain.coach.Coach
import dreamteam.domain.coach.CoachExplain
import dreamteam.domain.coach.CoachNote
import dreamteam.domain.coach.CoachReport
import dreamteam.domain.safety.SafetyEvaluation
import dreamteam.domain.safety.MedicalSafety
import dreamteam.domain.safety.SafetyGate
import dreamteam.domain.safety.SafetyGuardedGateway
import dreamteam.domain.safety.provisionedSafetyGateway
import dreamteam.domain.training.DeterministicPlanGenerator
import dreamteam.domain.training.GeneratedPlan
import dreamteam.domain.training.TrainingPlan
import dreamteam.server.coach.ZaiCoachProvider
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    // M8-C ([DRE-89](/DRE/issues/DRE-89)): the AI coach. The [ZaiCoachProvider] is
    // the ONLY LLM path in the system (#5: no LLM in the client). Env-gated — no
    // `DREAMTEAM_ZAI_API_KEY` ⇒ the provider reports unavailable ⇒ the coach's
    // deterministic fallback always stands (#4). Safety is in the domain layer
    // ([dreamteam.domain.coach.Coach]), not in trusting this provider.
    val coach = Coach(provider = ZaiCoachProvider())

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
            // persisted durably (ADR 0003). Baseline semantics: adaptation = None
            // (the log-driven counterpart is POST /v1/plans/recalculate).
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

                val gateway = provisionedGateway(medical, safetyEval)
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

            // POST /v1/plans/recalculate — the weekly adaptation loop (M3-B,
            // [DRE-51](/DRE/issues/DRE-51)). Loads the user's recent progress +
            // symptom logs, derives the fresh de-load-only AdaptationSignal, and
            // regenerates the plan through the SAME safety gate as /generate —
            // under a NEW versioned plan id ("{user}@{date}"), so the prior plan
            // is retained for audit/rollback, not overwritten. Still
            // deterministic, still gated, de-load-only (no "intensify" path).
            // Cadence is a caller policy: this is an on-demand operation, no
            // scheduler (YAGNI).
            post("/plans/recalculate") {
                val request = call.receive<PlanGenerateRequest>()
                val medical = request.medicalSafety
                val userId = request.userId.ifBlank { "seed-user" }
                val safetyEval = SafetyGate.evaluate(medical)

                // Same pre-LLM red-flag gate as /generate: a red flag closes
                // adaptation too — the loop never bypasses safety.
                if (!safetyEval.allowTrainingGeneration) {
                    call.respond(
                        io.ktor.http.HttpStatusCode.Conflict,
                        BlockedResponse(status = "blocked_red_flag", safety = safetyEval),
                    )
                    return@post
                }

                val gateway = provisionedGateway(medical, safetyEval)
                val progress = deps.progress.recentFor(userId, RECENT_LOG_LIMIT)
                val symptoms = deps.symptoms.recentFor(userId, RECENT_LOG_LIMIT)
                val generated = DeterministicPlanGenerator(gateway).recalculate(
                    userId = userId,
                    createdAt = LocalDate.now().toString(),
                    progress = progress,
                    symptoms = symptoms,
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

            // POST /v1/coach/explain — M8-C ([DRE-89](/DRE/issues/DRE-89))
            // "Спросить у AI": a short contextual cue for ONE exercise (NOT a
            // chat, per reviewer p.3.3). The client sends a structured request
            // (#5: no LLM in the client); the server calls Z.AI (GLM, Max think)
            // and returns a validated, phone-readable result. A red flag routes
            // to assessment (409) before any provider call (#1); a provider that
            // is absent/errors/times out ⇒ the deterministic fallback (#4).
            post("/coach/explain") {
                val request = call.receive<CoachExplainRequest>()
                // The provider call blocks on the JDK HttpClient; run it off the
                // Netty event loop so one slow Max-think call cannot stall peers.
                val result = withContext(Dispatchers.IO) {
                    coach.explain(exerciseId = request.exerciseId, medical = request.medicalSafety)
                }
                when (result) {
                    is CoachExplain.Blocked -> call.respond(
                        io.ktor.http.HttpStatusCode.Conflict,
                        CoachBlockedResponse(status = "blocked_red_flag", safety = result.safety),
                    )
                    is CoachExplain.Ok -> call.respond(
                        CoachExplainResponse(
                            status = "ok",
                            exerciseId = result.exerciseId,
                            summaryRu = result.summaryRu,
                            source = result.source.name.lowercase(),
                        ),
                    )
                }
            }

            // POST /v1/coach/report — M8-C "Сообщить коучу" CTA: the end-of-
            // workout report. The client sends the session's per-exercise notes
            // (M8-B) + medical; the server augments with its own recent
            // symptoms/progress logs for the user, then runs the coach. Returns
            // summary_ru + corrections[] + the gate-produced adapted_plan, with
            // the original_plan_id preserved so the UI can offer
            // "оригинал vs адаптация" (adaptation = default). All safety-gated.
            post("/coach/report") {
                val request = call.receive<CoachReportRequest>()
                val userId = request.userId.ifBlank { "seed-user" }
                val progress = deps.progress.recentFor(userId, RECENT_LOG_LIMIT)
                val symptoms = deps.symptoms.recentFor(userId, RECENT_LOG_LIMIT)
                val result = withContext(Dispatchers.IO) {
                    coach.report(
                        userId = userId,
                        createdAt = LocalDate.now().toString(),
                        medical = request.medicalSafety,
                        originalPlanId = request.originalPlanId,
                        notes = request.notes,
                        symptoms = symptoms,
                        progress = progress,
                    )
                }
                when (result) {
                    is CoachReport.Blocked -> call.respond(
                        io.ktor.http.HttpStatusCode.Conflict,
                        CoachBlockedResponse(status = "blocked_red_flag", safety = result.safety),
                    )
                    is CoachReport.Unavailable -> call.respond(
                        // Graceful degrade (DRE-99): the gateway blocked the baseline
                        // plan, not a red flag. 503 + a clean body so the client keeps
                        // the original plan instead of getting a 500 ClassCastException.
                        io.ktor.http.HttpStatusCode.ServiceUnavailable,
                        CoachUnavailableResponse(status = "plan_unavailable", originalPlanId = result.originalPlanId),
                    )
                    is CoachReport.Ok -> call.respond(result) // sealed type serializes to phone-readable JSON
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

// ---- M8-C coach routes ([DRE-89](/DRE/issues/DRE-89)) ----------------------

/** Request for POST /v1/coach/explain ("Спросить у AI" — one exercise cue). */
@Serializable
data class CoachExplainRequest(
    @SerialName("user_id") val userId: String = "",
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("medical_safety") val medicalSafety: MedicalSafety = MedicalSafety(),
)

/** Request for POST /v1/coach/report ("Сообщить коучу" — end-of-workout report). */
@Serializable
data class CoachReportRequest(
    @SerialName("user_id") val userId: String = "",
    @SerialName("medical_safety") val medicalSafety: MedicalSafety = MedicalSafety(),
    @SerialName("original_plan_id") val originalPlanId: String = "baseline-12w",
    val notes: List<CoachNote> = emptyList(),
)

/** The phone-readable explain result (200). */
@Serializable
data class CoachExplainResponse(
    val status: String,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("summary_ru") val summaryRu: String,
    val source: String, // "fallback" | "llm"
)

/** The phone-readable coach block response (409) — a pre-LLM red-flag block. */
@Serializable
data class CoachBlockedResponse(
    val status: String,
    val safety: SafetyEvaluation,
)

/**
 * The phone-readable graceful-degrade response (503) — the gateway blocked the
 * baseline plan (not a red flag); the UI keeps the original plan (DRE-99).
 */
@Serializable
data class CoachUnavailableResponse(
    val status: String,
    @SerialName("original_plan_id") val originalPlanId: String,
)

/**
 * Builds the provisioned gateway both plan routes share. Delegates to the shared
 * [provisionedSafetyGateway] so the plan routes, the M8-C coach, and the client
 * all use ONE gate wiring — a flagged-scoliosis request proposing a
 * heavy_axial_loading / loaded_flexion_rotation movement is BLOCKED here
 * regardless of caller (adaptation and baseline share one gate, never a bypass).
 */
private fun provisionedGateway(
    medical: MedicalSafety,
    safetyEval: SafetyEvaluation,
): SafetyGuardedGateway = provisionedSafetyGateway(medical, safetyEval)

/**
 * How many recent progress + symptom entries the recalc reads to derive the
 * AdaptationSignal. Generous for a weekly loop: trend detection needs >=2
 * points spanning >=1 week; this window covers multiple weeks of daily logs
 * while staying bounded. The pure derivation only uses first/last + latest-vs-
 * prior-union, so the exact limit just bounds how far back "recent" reaches.
 */
private const val RECENT_LOG_LIMIT = 30
