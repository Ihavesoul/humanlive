package dreamteam.server.coach

import dreamteam.server.module
import dreamteam.server.persistence.EncryptionKeys
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
import java.nio.file.Files

/**
 * M8-C ([DRE-89](/DRE/issues/DRE-89)) — pins the coach HTTP surface. Runs without
 * a `DREAMTEAM_ZAI_API_KEY`, so the [ZaiCoachProvider] reports unavailable and the
 * coach serves the **deterministic fallback** (#4) — the path that must always
 * hold. The LLM-validation contract is pinned at the domain layer
 * ([dreamteam.domain.coach.CoachTest]); these tests prove the routes wire it
 * correctly: phone-readable JSON out, red flag ⇒ 409 pre-LLM, fallback ⇒ 200
 * with the gated adapted plan + the preserved original_plan_id.
 */
class CoachRouteTest {
    private val testKey = EncryptionKeys.of(ByteArray(32) { (it + 1).toByte() })
    private fun tempDb(): String = Files.createTempFile("dreamteam-test", ".db").toString()

    @Test
    fun `coach report serves the deterministic fallback with the gated adapted plan`() =
        testApplication {
            application { module("jdbc:sqlite:${tempDb()}", testKey) }
            val response = client.post("/v1/coach/report") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "user_id": "seed-user",
                      "medical_safety": { "scoliosis_reported": true, "red_flags": [] },
                      "original_plan_id": "baseline-12w",
                      "notes": [
                        { "exercise_id": "split_squat", "note": "сильно стреляло в поясницу, боль" }
                      ]
                    }
                    """.trimIndent(),
                )
            }
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            // Phone-readable JSON shape: summary + corrections + adapted plan + preserved original.
            body shouldContain "\"summary_ru\""
            body shouldContain "\"adapted_plan\""
            body shouldContain "\"original_plan_id\": \"baseline-12w\""
            body shouldContain "\"source\": \"fallback\""
            // #1: the adapted plan is the gate-produced baseline subset — a known safe id is present.
            body shouldContain "split_squat"
            // No fabricated citation can appear in the fallback (no DOI/URL).
            (body.lowercase().contains("doi") || body.contains("https://")) shouldBe false
        }

    @Test
    fun `coach report blocks 409 on a red flag before any provider call`() =
        testApplication {
            application { module("jdbc:sqlite:${tempDb()}", testKey) }
            val response = client.post("/v1/coach/report") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "medical_safety": {
                        "scoliosis_reported": true,
                        "red_flags": ["new_bowel_or_bladder_dysfunction"]
                      },
                      "original_plan_id": "baseline-12w",
                      "notes": []
                    }
                    """.trimIndent(),
                )
            }
            response.status shouldBe HttpStatusCode.Conflict
            val body = response.bodyAsText()
            body shouldContain "\"blocked_red_flag\""
            body shouldContain "\"allow_training_generation\": false"
        }

    @Test
    fun `coach explain serves the deterministic fallback cue`() =
        testApplication {
            application { module("jdbc:sqlite:${tempDb()}", testKey) }
            val response = client.post("/v1/coach/explain") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "exercise_id": "split_squat",
                      "medical_safety": { "scoliosis_reported": true, "red_flags": [] }
                    }
                    """.trimIndent(),
                )
            }
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"summary_ru\""
            body shouldContain "\"source\": \"fallback\""
            body shouldContain "\"exercise_id\": \"split_squat\""
        }

    @Test
    fun `coach explain blocks 409 on a red flag`() =
        testApplication {
            application { module("jdbc:sqlite:${tempDb()}", testKey) }
            val response = client.post("/v1/coach/explain") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "exercise_id": "split_squat",
                      "medical_safety": { "red_flags": ["new_bowel_or_bladder_dysfunction"] }
                    }
                    """.trimIndent(),
                )
            }
            response.status shouldBe HttpStatusCode.Conflict
            response.bodyAsText() shouldContain "\"blocked_red_flag\""
        }
}
