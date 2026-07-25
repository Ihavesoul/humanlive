package dreamteam.app

import dreamteam.app.data.Profile
import dreamteam.domain.coach.CoachExplain
import dreamteam.domain.coach.CoachReport
import dreamteam.domain.coach.CoachSource
import dreamteam.domain.safety.MedicalSafety
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * M9-D ([DRE-125](/DRE/issues/DRE-125)) — pins the app→server coach transport.
 * The flag is default-off, so the paths that must always hold are:
 *
 * 1. **flag-off ⇒ local deterministic fallback** (#4): explain + report with no
 *    server produce the offline result (source = FALLBACK).
 * 2. **a server fallback body renders as the offline source** — a 200 with
 *    `source: "fallback"` decodes into [CoachExplain.Ok]/[CoachReport.Ok] whose
 *    view label is the offline one; an LLM body (`source: "llm"`) decodes to
 *    [CoachSource.LLM] (Phase-2 readiness, no key needed).
 * 3. **the gate still blocks red-flags pre-LLM** (#1): a server 409
 *    `blocked_red_flag` maps to [CoachExplain.Blocked]/[CoachReport.Blocked].
 * 4. **any transport failure ⇒ null ⇒ caller keeps the local fallback** (network
 *    null, 500, garbage body).
 *
 * Uses a fake [CoachHttpTransport] — the real [UrlConnCoachTransport] is the
 * env-gated, Phase-2-only HTTP path and is never exercised in CI.
 */
class CoachServerTransportTest {

    private val cleanProfile = Profile(
        sex = "male", age = 28, height = 188.0, weight = 83.2, bodyFat = null,
        scoliosisReported = true, redFlags = emptyList(), createdOn = "2026-07-25",
    )

    // --- #1: flag off ⇒ local deterministic fallback -------------------------

    @Test
    fun `flag off - explain serves the local deterministic fallback`() {
        val result = coachExplainForExercise("split_squat", cleanProfile, server = null)
        val ok = result.shouldBeInstanceOf<CoachExplain.Ok>()
        ok.source shouldBe CoachSource.FALLBACK
        coachExplainView(ok).sourceLabel shouldBe CoachStrings.SOURCE_FALLBACK
    }

    @Test
    fun `flag off - report serves the local deterministic fallback`() {
        val report = coachReportForSession(
            profile = cleanProfile,
            notes = emptyList(),
            symptoms = emptyList(),
            progress = emptyList(),
            today = "2026-07-25",
            server = null,
        ).shouldBeInstanceOf<CoachReport.Ok>()
        report.source shouldBe CoachSource.FALLBACK
        coachReportView(report).sourceLabel shouldBe CoachStrings.SOURCE_FALLBACK
    }

    // --- #2: a server body decodes into the domain type + the right source label

    @Test
    fun `a server fallback explain body renders as the offline source`() {
        val server = CoachServerClient(
            baseUrl = "http://test/v1",
            userId = "local",
            transport = stub(
                "/coach/explain",
                200,
                """
                {
                  "status": "ok",
                  "exercise_id": "split_squat",
                  "summary_ru": "Сплит-присед: держите нейтральное положение позвоночника.",
                  "source": "fallback"
                }
                """.trimIndent(),
            ),
        )
        val ok = server.explain("split_squat", medicalOf(cleanProfile)).shouldBeInstanceOf<CoachExplain.Ok>()
        ok.summaryRu shouldBe "Сплит-присед: держите нейтральное положение позвоночника."
        ok.source shouldBe CoachSource.FALLBACK
        coachExplainView(ok).sourceLabel shouldBe CoachStrings.SOURCE_FALLBACK
    }

    @Test
    fun `a server LLM explain body decodes to the LLM source (Phase-2 readiness)`() {
        val server = CoachServerClient(
            baseUrl = "http://test/v1",
            userId = "local",
            transport = stub(
                "/coach/explain",
                200,
                """
                {
                  "status": "ok",
                  "exercise_id": "split_squat",
                  "summary_ru": "Короткая подсказка от коуча по технике.",
                  "source": "llm"
                }
                """.trimIndent(),
            ),
        )
        val ok = server.explain("split_squat", medicalOf(cleanProfile)).shouldBeInstanceOf<CoachExplain.Ok>()
        ok.source shouldBe CoachSource.LLM
        coachExplainView(ok).sourceLabel shouldBe CoachStrings.SOURCE_LLM
    }

    @Test
    fun `a server fallback report body renders as the offline source`() {
        // Build a real CoachReport.Ok via the local coach, then serialize it the
        // way the server emits it (concrete Ok, no polymorphic type tag) — a
        // faithful 200 body without hand-writing the large adapted_plan tree.
        val localOk = coachReportForSession(
            profile = cleanProfile, notes = emptyList(), symptoms = emptyList(),
            progress = emptyList(), today = "2026-07-25", server = null,
        ) as CoachReport.Ok
        val serverBody = coachWireJson.encodeToString(CoachReport.Ok.serializer(), localOk)

        val server = CoachServerClient(
            baseUrl = "http://test/v1",
            userId = "local",
            transport = stub("/coach/report", 200, serverBody),
        )
        val report = server.report(medicalOf(cleanProfile), "baseline-12w", emptyList())
            .shouldBeInstanceOf<CoachReport.Ok>()
        report.source shouldBe CoachSource.FALLBACK
        report.originalPlanId shouldBe "baseline-12w"
        coachReportView(report).sourceLabel shouldBe CoachStrings.SOURCE_FALLBACK
    }

    @Test
    fun `coachExplainForExercise prefers the server result when a client is passed`() {
        val server = CoachServerClient(
            baseUrl = "http://test/v1",
            userId = "local",
            transport = stub(
                "/coach/explain",
                200,
                """{"status":"ok","exercise_id":"split_squat","summary_ru":"x","source":"llm"}""",
            ),
        )
        val ok = coachExplainForExercise("split_squat", cleanProfile, server = server)
            .shouldBeInstanceOf<CoachExplain.Ok>()
        ok.source shouldBe CoachSource.LLM
    }

    // --- #3: the gate still blocks red-flags pre-LLM -------------------------

    @Test
    fun `a server 409 blocked_red_flag maps to a pre-LLM block (explain)`() {
        val server = CoachServerClient(
            baseUrl = "http://test/v1",
            userId = "local",
            transport = stub(
                "/coach/explain",
                409,
                """
                {
                  "status": "blocked_red_flag",
                  "safety": {
                    "red_flag_gate_passed": false,
                    "allow_training_generation": false,
                    "allow_side_specific_content": false,
                    "warnings": ["red_flag:new_bowel_or_bladder_dysfunction"]
                  }
                }
                """.trimIndent(),
            ),
        )
        val result = server.explain("split_squat", medicalOf(cleanProfile))
        result.shouldBeInstanceOf<CoachExplain.Blocked>()
        (result.safety.allowTrainingGeneration) shouldBe false
    }

    @Test
    fun `a server 409 blocked_red_flag maps to a pre-LLM block (report)`() {
        val server = CoachServerClient(
            baseUrl = "http://test/v1",
            userId = "local",
            transport = stub(
                "/coach/report",
                409,
                """
                {
                  "status": "blocked_red_flag",
                  "safety": {
                    "red_flag_gate_passed": false,
                    "allow_training_generation": false,
                    "allow_side_specific_content": false,
                    "warnings": ["red_flag"]
                  }
                }
                """.trimIndent(),
            ),
        )
        val result = server.report(medicalOf(cleanProfile), "baseline-12w", emptyList())
        result.shouldBeInstanceOf<CoachReport.Blocked>()
        (result.safety.allowTrainingGeneration) shouldBe false
    }

    @Test
    fun `a server 503 plan_unavailable maps to the graceful degrade`() {
        val server = CoachServerClient(
            baseUrl = "http://test/v1",
            userId = "local",
            transport = stub(
                "/coach/report",
                503,
                """{"status":"plan_unavailable","original_plan_id":"baseline-12w"}""",
            ),
        )
        val result = server.report(medicalOf(cleanProfile), "baseline-12w", emptyList())
            .shouldBeInstanceOf<CoachReport.Unavailable>()
        result.originalPlanId shouldBe "baseline-12w"
    }

    // --- #4: any transport failure ⇒ null ⇒ caller keeps the local fallback ---

    @Test
    fun `a network failure returns null so the caller keeps the local fallback`() {
        val failing = CoachHttpTransport { _, _ -> null }
        val server = CoachServerClient("http://test/v1", "local", failing)
        server.explain("split_squat", medicalOf(cleanProfile)) shouldBe null
        server.report(medicalOf(cleanProfile), "baseline-12w", emptyList()) shouldBe null
    }

    @Test
    fun `an unexpected status or unparseable body returns null`() {
        val server500 = CoachServerClient("http://test/v1", "local", stub("/coach/explain", 500, "oops"))
        server500.explain("split_squat", medicalOf(cleanProfile)) shouldBe null

        val serverGarbage = CoachServerClient("http://test/v1", "local", stub("/coach/explain", 200, "not json"))
        serverGarbage.explain("split_squat", medicalOf(cleanProfile)) shouldBe null
    }

    // --- helpers --------------------------------------------------------------

    private fun medicalOf(profile: Profile): MedicalSafety = MedicalSafety(
        scoliosisReported = profile.scoliosisReported,
        redFlags = profile.redFlags,
    )

    /** A transport that returns a canned response for any URL containing [path]. */
    private fun stub(path: String, status: Int, body: String): CoachHttpTransport =
        CoachHttpTransport { url, _ -> if (path in url) CoachHttpResponse(status, body) else null }
}
