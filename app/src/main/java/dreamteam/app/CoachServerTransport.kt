package dreamteam.app

import dreamteam.domain.ExerciseId
import dreamteam.domain.coach.CoachExplain
import dreamteam.domain.coach.CoachNote
import dreamteam.domain.coach.CoachReport
import dreamteam.domain.coach.CoachSource
import dreamteam.domain.safety.MedicalSafety
import dreamteam.domain.safety.SafetyEvaluation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * M9-D ([DRE-125](/DRE/issues/DRE-125)): the app→server coach transport — the
 * "documented follow-up" to M8-C ([DRE-89](/DRE/issues/DRE-89)). When enabled and
 * reachable it calls `POST /v1/coach/{explain,report}` and decodes the
 * phone-readable result straight back into the shared [CoachExplain] /
 * [CoachReport] domain types — **no new view model** (#5 preserved: the only LLM
 * call stays server-side in [dreamteam.server.coach.ZaiCoachProvider]).
 *
 * ## Safety model (unchanged from M8-C — this is an enablement, not a new gate)
 *  - **#1 The gate is server-side too.** A red-flag request ⇒ the server returns
 *    `409 blocked_red_flag` *before any provider call*; [decodeExplain] /
 *    [decodeReport] map that to [CoachExplain.Blocked] / [CoachReport.Blocked].
 *  - **#4 Never stranded.** The transport is strictly *try-and-degrade*: any
 *    failure (flag off, network error, timeout, unexpected status, unparseable
 *    body) returns `null`, and the caller keeps the **local deterministic
 *    fallback** ([dreamteam.domain.coach.Coach] with `provider = null`).
 *
 * **Default off.** [coachServerEnabled] is `false` unless the operator sets
 * `DREAMTEAM_COACH_SERVER_ENABLED=true`. Flipping it live is Phase 2, gated by
 * the Z.AI spend approval on [DRE-111](/DRE/issues/DRE-111) + a provisioned
 * `DREAMTEAM_ZAI_API_KEY` server-side. With the flag off the network path is
 * never reached, so there is no main-thread network today; Phase 2 must move the
 * Compose caller into a coroutine before flipping the flag (Android blocks
 * network on the main thread).
 *
 * ponytail: uses `HttpURLConnection` (Android stdlib since API 1), NOT
 * `java.net.http.HttpClient` — that class is Android API 34+ only and this app's
 * minSdk is 26 (no core-library desugaring). Same "stdlib, no new dependency"
 * rule (ADR 0001), the variant that actually runs on the target.
 */
internal val coachWireJson: Json = Json { ignoreUnknownKeys = true; isLenient = true }

// --- feature flag (default off) ---------------------------------------------

/** Phase-2 switch. Off ⇒ the app always serves the local deterministic coach. */
internal val coachServerEnabled: Boolean =
    System.getenv("DREAMTEAM_COACH_SERVER_ENABLED")?.trim()?.equals("true", ignoreCase = true) == true

/** Base URL of the DreamTeam server's `/v1` coach API. */
internal val coachServerBaseUrl: String =
    System.getenv("DREAMTEAM_COACH_SERVER_URL")?.takeIf { it.isNotBlank() }
        ?: "http://10.0.2.2:8080/v1" // 10.0.2.2 = host loopback as seen from the Android emulator

/** Builds a client only when the flag is on; otherwise null ⇒ local fallback. */
internal fun coachServerClientOrNull(): CoachServerClient? =
    if (coachServerEnabled) CoachServerClient(coachServerBaseUrl, userId = "local") else null

// --- transport seam (so the degrade/null path is JVM-testable) ---------------

internal fun interface CoachHttpTransport {
    /** Returns the response, or `null` on any network/timeout failure (⇒ fallback). */
    fun post(url: String, jsonBody: String): CoachHttpResponse?
}

internal data class CoachHttpResponse(val status: Int, val body: String)

/**
 * The default transport: [HttpURLConnection] (Android stdlib). Synchronous; a
 * connect/read timeout throws ⇒ [post] returns `null` ⇒ the coach falls back (#4).
 * Never exercised in CI — tests inject a fake [CoachHttpTransport].
 */
internal object UrlConnCoachTransport : CoachHttpTransport {
    override fun post(url: String, jsonBody: String): CoachHttpResponse? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 30_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { os ->
                OutputStreamWriter(os, StandardCharsets.UTF_8).use { it.write(jsonBody) }
            }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            CoachHttpResponse(status, body)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}

// --- the client: encode → POST → decode into domain types -------------------

internal class CoachServerClient(
    private val baseUrl: String,
    private val userId: String,
    private val transport: CoachHttpTransport = UrlConnCoachTransport,
) {
    fun explain(exerciseId: ExerciseId, medical: MedicalSafety): CoachExplain? {
        val body = coachWireJson.encodeToString(
            ExplainRequest.serializer(),
            ExplainRequest(userId = userId, exerciseId = exerciseId, medicalSafety = medical),
        )
        val resp = transport.post("$baseUrl/coach/explain", body) ?: return null
        return decodeExplain(resp)
    }

    fun report(medical: MedicalSafety, originalPlanId: String, notes: List<CoachNote>): CoachReport? {
        val body = coachWireJson.encodeToString(
            ReportRequest.serializer(),
            ReportRequest(userId = userId, medicalSafety = medical, originalPlanId = originalPlanId, notes = notes),
        )
        val resp = transport.post("$baseUrl/coach/report", body) ?: return null
        return decodeReport(resp)
    }
}

private fun decodeExplain(resp: CoachHttpResponse): CoachExplain? = when (resp.status) {
    200 -> runCatching {
        val r = coachWireJson.decodeFromString(ExplainResponse.serializer(), resp.body)
        CoachExplain.Ok(exerciseId = r.exerciseId, summaryRu = r.summaryRu, source = parseSource(r.source))
    }.getOrNull()
    409 -> runCatching {
        CoachExplain.Blocked(coachWireJson.decodeFromString(BlockedResponse.serializer(), resp.body).safety)
    }.getOrNull()
    else -> null
}

private fun decodeReport(resp: CoachHttpResponse): CoachReport? = when (resp.status) {
    200 -> runCatching {
        // The server responds the concrete CoachReport.Ok (no polymorphic type tag);
        // ignoreUnknownKeys also tolerates a future sealed-type discriminator.
        coachWireJson.decodeFromString(CoachReport.Ok.serializer(), resp.body)
    }.getOrNull()
    409 -> runCatching {
        CoachReport.Blocked(coachWireJson.decodeFromString(BlockedResponse.serializer(), resp.body).safety)
    }.getOrNull()
    503 -> runCatching {
        // Graceful degrade (DRE-99): gateway blocked the baseline plan; reasons stay server-side.
        CoachReport.Unavailable(
            originalPlanId = coachWireJson.decodeFromString(UnavailableResponse.serializer(), resp.body).originalPlanId,
            reasons = emptyList(),
        )
    }.getOrNull()
    else -> null
}

private fun parseSource(source: String): CoachSource = when (source.lowercase().trim()) {
    "llm" -> CoachSource.LLM
    else -> CoachSource.FALLBACK // unknown / missing ⇒ the safe (offline) label
}

// --- wire DTOs (mirror the server's coach request/response shapes) -----------

@Serializable
private data class ExplainRequest(
    @SerialName("user_id") val userId: String,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("medical_safety") val medicalSafety: MedicalSafety,
)

@Serializable
private data class ReportRequest(
    @SerialName("user_id") val userId: String,
    @SerialName("medical_safety") val medicalSafety: MedicalSafety,
    @SerialName("original_plan_id") val originalPlanId: String,
    val notes: List<CoachNote>,
)

@Serializable
private data class ExplainResponse(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("summary_ru") val summaryRu: String,
    val source: String,
)

@Serializable
private data class BlockedResponse(val safety: SafetyEvaluation)

@Serializable
private data class UnavailableResponse(@SerialName("original_plan_id") val originalPlanId: String)
