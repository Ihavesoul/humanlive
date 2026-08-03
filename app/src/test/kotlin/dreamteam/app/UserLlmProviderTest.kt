package dreamteam.app

import dreamteam.app.data.Profile
import dreamteam.domain.coach.Coach
import dreamteam.domain.coach.CoachExplain
import dreamteam.domain.coach.CoachSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * DRE-175 — pins the user-creds direct-LLM path. The credential store itself is
 * Android-only (Keystore), so what is verified here is the testable core:
 *
 * 1. **A 200 with `choices[0].message.content` ⇒ the provider returns it**, and a
 *    [Coach] wired to it surfaces the LLM source label (Phase-1 readiness: the
 *    "stub" complaint is gone once the user enters a working URL+token).
 * 2. **Any failure ⇒ null ⇒ the shared coach keeps the deterministic fallback**
 *    (#4: network null, non-2xx, garbage body, blank token).
 * 3. **Precedence** — [coachExplainForExercise] prefers the user-coach, and when
 *    the user-coach's provider fails it degrades to the deterministic fallback
 *    (never strands the user, even mid-LLM-failure).
 * 4. **The pre-LLM red-flag gate still blocks** when a user-coach is wired (#1:
 *    the LLM cannot bypass the gate; a red-flag profile yields Blocked, not a cue).
 */
class UserLlmProviderTest {

    private val cleanProfile = Profile(
        sex = "male", age = 28, height = 188.0, weight = 83.2, bodyFat = null,
        scoliosisReported = true, redFlags = emptyList(), createdOn = "2026-07-25",
    )
    private val redFlagProfile = cleanProfile.copy(redFlags = listOf("new_bowel_or_bladder_dysfunction"))

    // --- #1: a 200 with content ⇒ provider returns it + the coach surfaces LLM --

    @Test
    fun `a valid LLM response makes the provider return the phone-readable content`() {
        val provider = UserLlmProvider(
            baseUrl = "https://example.test/v4",
            token = "key",
            model = "glm-4.6",
            transport = stubLlm(
                200,
                """{"choices":[{"message":{"content":"{\"summary_ru\":\"Держите нейтральное положение.\"}"}}]}""",
            ),
        )
        provider.complete(systemPrompt = "sys", userPayloadJson = "{}") shouldBe
            "{\"summary_ru\":\"Держите нейтральное положение.\"}"
    }

    @Test
    fun `a Coach wired to the user provider surfaces the LLM source on explain`() {
        val provider = UserLlmProvider(
            baseUrl = "https://example.test/v4",
            token = "key",
            model = "glm-4.6",
            transport = stubLlm(
                200,
                """{"choices":[{"message":{"content":"{\"summary_ru\":\"Короткая подсказка по технике.\"}"}}]}""",
            ),
        )
        val userCoach = Coach(provider = provider)
        val ok = coachExplainForExercise("split_squat", cleanProfile, server = null, userCoach = userCoach)
            .shouldBeInstanceOf<CoachExplain.Ok>()
        ok.source shouldBe CoachSource.LLM
        coachExplainView(ok).sourceLabel shouldBe CoachStrings.SOURCE_LLM
    }

    // --- #2: any failure ⇒ null ⇒ fallback ------------------------------------

    @Test
    fun `a network failure returns null so the coach keeps the fallback`() {
        val provider = UserLlmProvider(
            baseUrl = "https://example.test/v4", token = "key", model = "glm-4.6",
            transport = LlmHttpTransport { _, _, _ -> null },
        )
        provider.complete("sys", "{}") shouldBe null
        // Wired into the coach, the failure degrades to the deterministic fallback (#4).
        val ok = coachExplainForExercise(
            "split_squat", cleanProfile, server = null, userCoach = Coach(provider = provider),
        ).shouldBeInstanceOf<CoachExplain.Ok>()
        ok.source shouldBe CoachSource.FALLBACK
    }

    @Test
    fun `a non-2xx status or unparseable body returns null`() {
        val provider500 = UserLlmProvider(
            baseUrl = "https://example.test/v4", token = "key", model = "glm-4.6",
            transport = stubLlm(500, "oops"),
        )
        provider500.complete("sys", "{}") shouldBe null

        val providerGarbage = UserLlmProvider(
            baseUrl = "https://example.test/v4", token = "key", model = "glm-4.6",
            transport = stubLlm(200, "not json"),
        )
        providerGarbage.complete("sys", "{}") shouldBe null
    }

    @Test
    fun `a blank token returns null (no key ⇒ unavailable ⇒ fallback)`() {
        val provider = UserLlmProvider(
            baseUrl = "https://example.test/v4", token = "  ", model = "glm-4.6",
            transport = stubLlm(200, """{"choices":[{"message":{"content":"x"}}]}"""),
        )
        provider.complete("sys", "{}") shouldBe null
    }

    // --- #3: precedence — user coach wins, degrades to fallback on failure ------

    @Test
    fun `coachForUserCreds builds a coach only when creds are present`() {
        coachForUserCreds(null) shouldBe null
        coachForUserCreds(CoachCredentials("https://x/v4", "", "glm-4.6"))?.let {
            // A blank-token cred still builds a Coach; the provider self-reports
            // unavailable (#2) so the fallback stands. The factory never throws.
        } ?: error("expected a coach")
    }

    @Test
    fun `coachExplainForExercise prefers the user coach over the server transport`() {
        // A server stub that would return LLM, but a user coach that fails ⇒ the
        // user coach owns the result (and its own fallback stands). Precedence.
        val server = CoachServerClient(
            baseUrl = "http://test/v1", userId = "local",
            transport = stub(
                "/coach/explain", 200,
                """{"status":"ok","exercise_id":"split_squat","summary_ru":"server","source":"llm"}""",
            ),
        )
        val userCoach = Coach(
            provider = UserLlmProvider(
                baseUrl = "https://x/v4", token = "key", model = "glm-4.6",
                transport = LlmHttpTransport { _, _, _ -> null }, // fails
            ),
        )
        val ok = coachExplainForExercise("split_squat", cleanProfile, server = server, userCoach = userCoach)
            .shouldBeInstanceOf<CoachExplain.Ok>()
        // User coach failed ⇒ its deterministic fallback (FALLBACK), NOT the server's LLM.
        ok.source shouldBe CoachSource.FALLBACK
    }

    // --- #4: the pre-LLM gate still blocks a red-flag profile -----------------

    @Test
    fun `a red-flag profile blocks even when a user LLM provider is wired`() {
        val userCoach = Coach(
            provider = UserLlmProvider(
                baseUrl = "https://x/v4", token = "key", model = "glm-4.6",
                transport = stubLlm(200, """{"choices":[{"message":{"content":"{\"summary_ru\":\"x\"}"}}]}"""),
            ),
        )
        val result = coachExplainForExercise("split_squat", redFlagProfile, server = null, userCoach = userCoach)
        result.shouldBeInstanceOf<CoachExplain.Blocked>()
        (result.safety.allowTrainingGeneration) shouldBe false
    }

    // DRE-175: the new Settings screen strings join the app-wide banned-phrase
    // scan (same list as every other authored surface — no new claim ships unscanned).
    private val banned = listOf(
        "диагноз", "диагности",
        "лечит", "лечение", "лечим", "вылеч", "излеч", "исцела", "исцели",
        "болезнь",
        "у вас", "вы больн", "вы здоровы", "ваш диагноз",
        "предписываю", "назначаю", "прописываю",
        "diagnos", "treat", "cure", "heal", "disease", "you have", "you are", "prescribe",
    )

    @Test
    fun `no authored Settings string contains a banned medical-claim phrase`() {
        SettingsStrings.all.forEach { text ->
            val lower = text.lowercase()
            banned.forEach { b -> (b !in lower) shouldBe true }
        }
    }

    // --- helpers --------------------------------------------------------------

    /** An LLM transport that returns a canned response for any URL. */
    private fun stubLlm(status: Int, body: String): LlmHttpTransport =
        LlmHttpTransport { _, _, _ -> CoachHttpResponse(status, body) }

    /** A coach-server transport stub (reused shape from CoachServerTransportTest). */
    private fun stub(path: String, status: Int, body: String): CoachHttpTransport =
        CoachHttpTransport { url, _ -> if (path in url) CoachHttpResponse(status, body) else null }
}
