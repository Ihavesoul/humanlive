package dreamteam.server

import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.symptom.Symptom
import dreamteam.server.persistence.EncryptionKeys
import dreamteam.server.persistence.SqliteRepositories
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * M3-B ([DRE-51](/DRE/issues/DRE-51)) done-when (server side): the weekly
 * recalculation loop `POST /v1/plans/recalculate` reads the user's logged
 * progress + symptoms, derives the fresh de-load-only AdaptationSignal,
 * regenerates the plan through the SAME safety gate as `/generate`, and
 * persists it as a NEW versioned plan — the prior baseline is retained and
 * discoverable via `historyFor`, the active version is the recalc.
 *
 * End-to-end: a baseline is generated first (POST /generate), a rapid-weight-loss
 * log set is seeded directly into the same DB, then POST /recalculate must
 * (a) return 200 with a de-loaded plan, and (c) leave BOTH versions retained +
 * retrievable, with the recalc as current.
 */
class PlanRecalculateRouteTest {

    @TempDir
    lateinit var tempDir: Path

    private val testKey = EncryptionKeys.of(ByteArray(32) { (it + 1).toByte() })

    private fun dbPath(): Path = tempDir.resolve("dreamteam-recalc-test.db")

    @Test
    fun `recalculate de-loads from logs and retains the prior version as a new version`() =
        testApplication {
            val jdbc = "jdbc:sqlite:${dbPath()}"
            application { module(jdbc, testKey) }

            // 1. Baseline first: POST /generate persists "baseline-12w" for the user.
            val baseline = client.post("/v1/plans/generate") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "user_id": "seed-user",
                      "medical_safety": { "scoliosis_reported": false, "red_flags": [] }
                    }
                    """.trimIndent(),
                )
            }
            baseline.status shouldBe HttpStatusCode.OK

            // 2. Seed a rapid-weight-loss log set directly into the same DB
            //    (no append route yet): 80kg -> 78.4kg over 2 weeks => DeLoad.
            //    WAL: the committed write is visible to the server's connection.
            SqliteRepositories.open(jdbc, testKey).use { r ->
                r.progress.append(ProgressEntry("p1", "seed-user", "2026-07-14", 80.0))
                r.progress.append(ProgressEntry("p2", "seed-user", "2026-07-28", 78.4))
            }

            // 3. POST /recalculate: must regenerate through the gate + persist a
            //    new versioned plan (id "seed-user@{today}").
            val recalc = client.post("/v1/plans/recalculate") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "user_id": "seed-user",
                      "medical_safety": { "scoliosis_reported": false, "red_flags": [] }
                    }
                    """.trimIndent(),
                )
            }
            recalc.status shouldBe HttpStatusCode.OK
            val body = recalc.bodyAsText()
            body shouldContain "\"status\": \"ok\""
            body shouldContain "\"evidenceRefs\"" // still evidence-linked

            // 4. Persistence + versioning: both versions retained, recalc is current,
            //    and the recalc actually de-loaded vs the baseline.
            SqliteRepositories.open(jdbc, testKey).use { r ->
                val history = r.plans.historyFor("seed-user")
                history shouldHaveSize 2
                val ids = history.map { it.id }.toSet()
                ids shouldBe setOf("baseline-12w", "seed-user@${LocalDate.now()}")

                val current = r.plans.currentFor("seed-user")
                current?.id shouldBe "seed-user@${LocalDate.now()}"

                // (a) real de-load: week 4 is a 3-set build week; recalc drops to 2.
                val baselinePlan = history.first { it.id == "baseline-12w" }
                val recalcPlan = history.first { it.id != "baseline-12w" }
                val baselineWeek4 = baselinePlan.weeks.first { it.weekNumber == 4 }.setsMain
                val recalcWeek4 = recalcPlan.weeks.first { it.weekNumber == 4 }.setsMain
                baselineWeek4 shouldBe 3
                recalcWeek4 shouldBe 2
            }
        }

    @Test
    fun `a red flag closes recalculation just like generation`() =
        testApplication {
            application { module("jdbc:sqlite:${dbPath()}", testKey) }
            val response = client.post("/v1/plans/recalculate") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "user_id": "seed-user",
                      "medical_safety": {
                        "scoliosis_reported": true,
                        "red_flags": ["new_bowel_or_bladder_dysfunction"]
                      }
                    }
                    """.trimIndent(),
                )
            }
            response.status shouldBe HttpStatusCode.Conflict
            val body = response.bodyAsText()
            body shouldContain "\"status\": \"blocked_red_flag\""
            body shouldContain "\"allow_training_generation\": false"
        }
}
