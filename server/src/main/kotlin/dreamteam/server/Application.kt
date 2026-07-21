package dreamteam.server

import dreamteam.domain.safety.MedicalSafety
import dreamteam.domain.safety.SafetyGate
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
import kotlinx.serialization.json.Json

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module).start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json(Json { prettyPrint = true; ignoreUnknownKeys = true })
    }
    routing {
        // Infra liveness probe — no medical data, no claims.
        get("/health") {
            call.respond(mapOf("status" to "ok", "service" to "dreamteam-server"))
        }

        // ---- /v1 skeleton (ADR 0001 / DRE-8) ---------------------------------
        // Versioned API surface. Only the deterministic endpoints are wired
        // here; every endpoint the client can reach runs the safety gate and
        // never trusts a provider to enforce it (see specs/Safety_Threat_Model.md).
        route("/v1") {
            // Deterministic, pre-LLM. The verdict is binding: no client can
            // override it. Implements ADR 0001 invariants #1 (safety = code)
            // and #3 (side-specific lock). See specs/SDD.md §2.4.
            post("/safety/evaluate") {
                val safety = call.receive<MedicalSafety>()
                call.respond(SafetyGate.evaluate(safety))
            }

            // Pending endpoints (not wired — listed so the skeleton surface is
            // explicit and ownership is unambiguous):
            //   POST /v1/calculate         deterministic energy/macro math;
            //                              needs the PoC's exact Cunningham
            //                              coefficient to match the contract
            //                              reference values (follow-up).
            //   POST /v1/plans/generate    LLM path + deterministic fallback;
            //                              LLM & Safety Orchestrator (DRE-7).
            //   POST /v1/plans/validate    evidence/exercise allowlist + schema;
            //                              LLM & Safety Orchestrator (DRE-7).
            //   POST /v1/weekly-adjustment deterministic adaptation; needs the
            //                              check-in repository (DRE-6).
        }
    }
}
