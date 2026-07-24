package dreamteam.server.coach

import dreamteam.domain.coach.CoachProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * M8-C ([DRE-89](/DRE/issues/DRE-89)): the Z.AI provider — the ONLY place an LLM
 * is called (#5: no LLM in the client). Calls Z.AI's OpenAI-compatible
 * `/chat/completions` with the configured GLM model and **thinking enabled (Max
 * think)**, then returns the raw phone-readable JSON the [Coach] parses+validates.
 *
 * Returns `null` on any unavailable / failed / timed-out / unparseable outcome —
 * the coach then keeps the **deterministic fallback** (#4: the user is never
 * stranded). The safety guarantee is *not* this provider succeeding; it is the
 * domain layer validating + gating whatever comes back. So a live failure degrades
 * silently to the proven fallback.
 *
 * **Env config** (deployment secrets — never committed):
 *  - `DREAMTEAM_ZAI_API_KEY` — the bearer key. Absent ⇒ provider reports
 *    unavailable (returns null) ⇒ fallback. This is the default in dev/CI.
 *  - `DREAMTEAM_ZAI_BASE_URL` — defaults to Z.AI's Paas v4 endpoint.
 *  - `DREAMTEAM_ZAI_MODEL` — defaults to `glm-4.6` (Z.AI's current flagship with
 *    a thinking mode). The M8-C spec targets **GLM 5.2 / Max think**; set this to
 *    `glm-5.2` (or Z.AI's then-current thinking model id) when it ships. The
 *    plumbing is model-agnostic: it is a string in the request body.
 *  - `DREAMTEAM_ZAI_TIMEOUT_MS` — explicit request timeout (default 90s; Max-think
 *    is slow by design). The UI never hangs: a timeout ⇒ null ⇒ fallback.
 *
 * Uses the **JDK stdlib** `java.net.http.HttpClient` (no new dependency — ADR
 * 0001 "boring tech, small surface"). ponytail: the live Z.AI call itself cannot
 * be exercised in CI without a key; what *is* verified is the degrade-to-fallback
 * behaviour (`null`) and the domain-layer validation that rejects bad output
 * ([dreamteam.domain.coach.CoachTest]). The exact Z.AI request/response shape is
 * the documented, env-gated adapter boundary.
 */
class ZaiCoachProvider internal constructor(
    private val apiKey: String? = System.getenv("DREAMTEAM_ZAI_API_KEY")?.takeIf { it.isNotBlank() },
    private val baseUrl: String = System.getenv("DREAMTEAM_ZAI_BASE_URL")?.takeIf { it.isNotBlank() }
        ?: "https://api.z.ai/api/paas/v4",
    // M8-C target: GLM 5.2 / Max think. Default to glm-4.6 (current thinking flagship)
    // until Z.AI ships 5.2 under that id; the env var is the switch.
    private val model: String = System.getenv("DREAMTEAM_ZAI_MODEL")?.takeIf { it.isNotBlank() } ?: "glm-4.6",
    private val timeoutMs: Long = System.getenv("DREAMTEAM_ZAI_TIMEOUT_MS")?.trim()?.toLongOrNull() ?: 90_000L,
    private val transport: HttpTransport = JdkHttpTransport,
) : CoachProvider {

    override fun complete(systemPrompt: String, userPayloadJson: String): String? {
        // #4: no key ⇒ unavailable ⇒ coach falls back. Never throw to the caller.
        val key = apiKey ?: return null
        val body = try {
            ChatRequestJson(
                model = model,
                messages = listOf(
                    MessageJson("system", systemPrompt),
                    MessageJson("user", userPayloadJson),
                ),
                // Z.AI thinking flag: enables the deep self-check ("Max think") the
                // spec asks for. The model returns only `content` to the app; its
                // internal reasoning stays server-side (phone-readable JSON out).
                thinking = ThinkingJson(enabled = true),
                temperature = 0.2,
            ).toJson()
        } catch (e: Exception) {
            return null
        }
        val response = try {
            transport.postJson("$baseUrl/chat/completions", key, body, timeoutMs)
        } catch (e: Exception) {
            return null // network error / timeout ⇒ fallback
        }
        if (response.status != HttpStatusCode.OK.value) return null
        return parseContent(response.body)
    }

    /** Extract `choices[0].message.content` (the phone-readable JSON) from a Z.AI response. */
    private fun parseContent(body: String): String? =
        runCatching {
            val resp = zaiJson.decodeFromString<ChatResponseJson>(body)
            resp.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
        }.getOrNull()
}

// --- Z.AI OpenAI-compatible wire shapes --------------------------------------

@Serializable
private data class ChatRequestJson(
    val model: String,
    val messages: List<MessageJson>,
    val thinking: ThinkingJson? = null,
    val temperature: Double = 0.2,
    @SerialName("max_tokens") val maxTokens: Int = 2048,
) {
    fun toJson(): String = zaiJson.encodeToString(serializer(), this)
}

@Serializable
private data class MessageJson(val role: String, val content: String)

/** Z.AI thinking toggle. `type = "enabled"` turns on the deep reasoning pass. */
@Serializable
private data class ThinkingJson(val type: String = "enabled") {
    val enabled: Boolean get() = type == "enabled"
    constructor(enabled: Boolean) : this(if (enabled) "enabled" else "disabled")
}

@Serializable
private data class ChatResponseJson(val choices: List<ChoiceJson> = emptyList())

@Serializable
private data class ChoiceJson(val message: ResponseMessageJson = ResponseMessageJson())

@Serializable
private data class ResponseMessageJson(val content: String? = null)

private val zaiJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// --- transport seam (so the timeout/null path is the unit-testable boundary) --

/** A minimal POST-JSON-and-return transport. The default uses the JDK HttpClient. */
internal fun interface HttpTransport {
    /** Returns the response body + status, or throws on network/timeout failure. */
    fun postJson(url: String, bearerKey: String, jsonBody: String, timeoutMs: Long): TransportResponse
}

internal data class TransportResponse(val status: Int, val body: String)

/**
 * The default transport: JDK 17 `HttpClient` (stdlib — no new dependency). A
 * synchronous send with an explicit [timeoutMs]; on timeout throws
 * `HttpTimeoutException` → the provider catches it → `null` → fallback (#4).
 */
internal object JdkHttpTransport : HttpTransport {
    override fun postJson(url: String, bearerKey: String, jsonBody: String, timeoutMs: Long): TransportResponse {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(timeoutMs))
            .header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            .header(HttpHeaders.Authorization, "Bearer $bearerKey")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return TransportResponse(response.statusCode(), response.body())
    }
}
