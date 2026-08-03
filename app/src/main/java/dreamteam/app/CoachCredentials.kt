package dreamteam.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dreamteam.domain.coach.Coach
import dreamteam.domain.coach.CoachProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * DRE-175 — the user's own AI-coach credentials (URL + token + model), stored
 * encrypted-at-rest so "Спросить у AI" can call the LLM **directly from the app**
 * with the user's key (the fix for "AI ничего не делает": the operator-provisioned
 * server key is not wired ([DRE-130](/DRE/issues/DRE-130), todo), so this is the
 * path that actually lights the feature up).
 *
 * ## Why this is safe to add (it does not weaken #1/#2/#4/#5)
 *  - **#1 Gate stays in code, pre-LLM.** The provider is injected into the shared
 *    [Coach], which runs [dreamteam.domain.safety.SafetyGate] and the assignment
 *    gateway BEFORE the provider is ever called, and validates the provider text
 *    (no fabricated citation, no medical claim) before surfacing it. The LLM
 *    cannot bypass or dismiss the gate.
 *  - **#4 Never stranded.** A missing/blank token ⇒ [load] returns null ⇒ no
 *    provider ⇒ the existing deterministic fallback stands exactly as today.
 *  - **#5 single LLM seam.** This is the one new [CoachProvider] impl; the rest
 *    of the app keeps rendering the same [CoachExplain]/[CoachReport] types.
 *
 * ## Encryption (the existing AES-at-rest pattern, device-side)
 *  - [CoachCredentialStore] mirrors the server's [dreamteam.server.persistence.PayloadCipher]
 *    shape (AES-256-GCM, `nonce + ciphertext`) using `javax.crypto` only — no new
 *    dependency (ADR 0001). The one difference is the key source: on-device the
 *    key is generated + held by the **Android Keystore** (hardware-backed where
 *    available), not an injected env secret. The SharedPreferences file holds
 *    only ciphertext — never the plaintext token (NOT plaintext SharedPreferences).
 *  - Reuses the bundled prompts via [Coach]: the user-message payloads +
 *    `COACH_SYSTEM_PROMPT` are built by the shared domain layer, so no prompt is
 *    authored here (DRE-175 scope: "переиспользовать, не писать новые").
 */
@Serializable
internal data class CoachCredentials(
    @SerialName("base_url") val baseUrl: String,
    @SerialName("token") val token: String,
    @SerialName("model") val model: String,
)

/**
 * The on-device encrypted store for [CoachCredentials]. Android Keystore-backed
 * AES-GCM; the SharedPreferences file holds a single base64 ciphertext blob.
 *
 * Not JVM-unit-tested (Keystore + SharedPreferences need an Android runtime) —
 * like [dreamteam.app.data.LocalDatabase] it is an Android edge. The companion
 * [coachForUserCreds] + [UserLlmProvider] carry the testable logic.
 */
internal class CoachCredentialStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The decrypted credentials, or null when nothing is saved / unreadable ⇒ fallback. */
    fun load(): CoachCredentials? {
        val blob = prefs.getString(KEY, null)?.let { Base64.getDecoder().decode(it) } ?: return null
        return runCatching {
            credsStoreJson.decodeFromString(
                CoachCredentials.serializer(),
                String(decrypt(blob), StandardCharsets.UTF_8),
            )
        }.getOrNull()
    }

    /** Encrypt + persist [creds]. A blank token is a "clear" (see [clear]). */
    fun save(creds: CoachCredentials) {
        if (creds.token.isBlank()) return clear()
        val ct = encrypt(
            credsStoreJson.encodeToString(CoachCredentials.serializer(), creds).toByteArray(StandardCharsets.UTF_8),
        )
        prefs.edit().putString(KEY, Base64.getEncoder().encodeToString(ct)).apply()
    }

    /** Drop the stored credentials ⇒ next coach call uses the deterministic fallback. */
    fun clear() = prefs.edit().remove(KEY).apply()

    private fun key(): java.security.Key {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.getKey(KEY_ALIAS, null)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain) // 12-byte Keystore GCM IV + ciphertext+tag
    }

    private fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "Ciphertext too short to contain an IV." }
        val iv = blob.copyOfRange(0, IV_LEN)
        val ct = blob.copyOfRange(IV_LEN, blob.size)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(ct)
    }

    companion object {
        private const val PREFS = "dreamteam_coach_creds"
        private const val KEY = "creds_blob"
        private const val KEY_ALIAS = "dreamteam_coach_creds_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
    }
}

private val credsStoreJson = Json { ignoreUnknownKeys = true }

/**
 * Build a [Coach] wired to the user's own [UserLlmProvider] when [creds] are
 * present, else null ⇒ the caller keeps the deterministic fallback (#4). Pure;
 * reading creds is the caller's job (so a Settings save is reflected next click).
 */
internal fun coachForUserCreds(creds: CoachCredentials?): Coach? =
    creds?.let { Coach(provider = UserLlmProvider(it.baseUrl, it.token, it.model)) }

// --- the OpenAI-compatible provider (same wire shape as the server's Z.AI) ---

/** A POST-JSON-with-bearer transport; injectable so [UserLlmProvider] is JVM-testable. */
internal fun interface LlmHttpTransport {
    /** Returns the response, or null on any network/timeout failure (⇒ fallback). */
    fun post(url: String, bearer: String, jsonBody: String): CoachHttpResponse?
}

/**
 * The default transport: [HttpURLConnection] (Android stdlib since API 1), NOT
 * `java.net.http.HttpClient` (Android API 34+ only; this app's minSdk is 26).
 * Same stdlib rule as [UrlConnCoachTransport] / [dreamteam.server.coach.JdkHttpTransport].
 * Synchronous; a timeout throws ⇒ [post] returns null ⇒ fallback (#4).
 */
internal object UrlConnLlmTransport : LlmHttpTransport {
    override fun post(url: String, bearer: String, jsonBody: String): CoachHttpResponse? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 90_000 // Max-think is slow by design; timeout ⇒ null ⇒ fallback
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $bearer")
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

/**
 * Calls the user's OpenAI-compatible `/chat/completions` endpoint with their key
 * (default Z.AI GLM, think = Max) and returns the raw phone-readable JSON the
 * shared [Coach] parses + validates. Returns null on any failure (missing key,
 * network error, non-2xx, unparseable body) ⇒ the coach keeps the deterministic
 * fallback (#4). Mirrors the server's [dreamteam.server.coach.ZaiCoachProvider]
 * request/response shape exactly so the same model behaves the same way.
 *
 * ponytail: the live endpoint is never exercised in CI (no key); what is verified
 * is the degrade-to-null path + the [Coach]-wired integration (see
 * [UserLlmProviderTest]).
 */
internal class UserLlmProvider(
    private val baseUrl: String,
    private val token: String,
    private val model: String,
    private val transport: LlmHttpTransport = UrlConnLlmTransport,
) : CoachProvider {

    override fun complete(systemPrompt: String, userPayloadJson: String): String? {
        if (token.isBlank()) return null // #4: no key ⇒ unavailable ⇒ fallback
        val body = runCatching {
            ChatRequestJson(
                model = model,
                messages = listOf(
                    MessageJson("system", systemPrompt),
                    MessageJson("user", userPayloadJson),
                ),
                // Z.AI thinking flag: enables the deep self-check ("Max think") the
                // coach prompt asks for. The model returns only `content` to the app.
                thinking = ThinkingJson(enabled = true),
                temperature = 0.2,
            ).toJson()
        }.getOrNull() ?: return null
        val resp = transport.post(chatCompletionsUrl(), token, body) ?: return null
        if (resp.status !in 200..299) return null
        return parseContent(resp.body)
    }

    /** Append `/chat/completions` unless the user already entered the full path. */
    private fun chatCompletionsUrl(): String {
        val trimmed = baseUrl.trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) trimmed else "$trimmed/chat/completions"
    }

    private fun parseContent(body: String): String? =
        runCatching {
            llmJson.decodeFromString<ChatResponseJson>(body)
                .choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
        }.getOrNull()
}

// --- OpenAI-compatible wire shapes (mirror dreamteam.server.coach) -----------

@Serializable
private data class ChatRequestJson(
    val model: String,
    val messages: List<MessageJson>,
    val thinking: ThinkingJson? = null,
    val temperature: Double = 0.2,
    @SerialName("max_tokens") val maxTokens: Int = 2048,
) {
    fun toJson(): String = llmJson.encodeToString(serializer(), this)
}

@Serializable
private data class MessageJson(val role: String, val content: String)

/** Z.AI thinking toggle: `type = "enabled"` turns on the deep reasoning pass. */
@Serializable
private data class ThinkingJson(val type: String = "enabled") {
    constructor(enabled: Boolean) : this(if (enabled) "enabled" else "disabled")
}

@Serializable
private data class ChatResponseJson(val choices: List<ChoiceJson> = emptyList())

@Serializable
private data class ChoiceJson(val message: ResponseMessageJson = ResponseMessageJson())

@Serializable
private data class ResponseMessageJson(val content: String? = null)

private val llmJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
