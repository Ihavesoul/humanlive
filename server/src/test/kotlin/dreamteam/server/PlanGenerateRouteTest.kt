package dreamteam.server

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import dreamteam.server.persistence.EncryptionKeys
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * M2-A done-when #1 (server side): `/v1/plans/generate` serves the deterministic
 * baseline plan **produced exclusively via SafetyGuardedGateway.surface()**, and
 * a blocked candidate (red flag) returns 409 — never a 200 with a hole in it.
 * The vetted plan is persisted durably (ADR 0003).
 */
class PlanGenerateRouteTest {

    private val testKey = EncryptionKeys.of(ByteArray(32) { (it + 1).toByte() })

    private fun tempDb(): String = Files.createTempFile("dreamteam-plan-test", ".db").toString()

    @Test
    fun `serves the deterministic baseline plan via the safety gateway`() =
        testApplication {
            application { module("jdbc:sqlite:${tempDb()}", testKey) }
            // Seed profile: scoliosis reported, no red flags, no current curve
            // data => side-specific stays locked; generic baseline is allowed.
            val response =
                client.post("/v1/plans/generate") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "user_id": "seed-user",
                          "medical_safety": {
                            "scoliosis_reported": true,
                            "red_flags": [],
                            "current_curve_data_available": false,
                            "clinician_curve_specific_plan_available": false
                          }
                        }
                        """.trimIndent(),
                    )
                }
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            // The deterministic 12-week baseline surfaced through the gate.
            body shouldContain "\"status\": \"ok\""
            body shouldContain "\"weekNumber\": 1"
            body shouldContain "\"weekNumber\": 12"
            // Side-specific stays locked (deterministic verdict carried through).
            body shouldContain "\"allow_side_specific_content\": false"
            // Every surfaced assignment carries evidence (no unsourced output).
            body shouldContain "\"evidenceRefs\""
            // Baseline nutrition target ships with its evidence link.
            body shouldContain "\"targetKcal\": 2300"
            body shouldContain "MIFFLIN-1990"
        }

    @Test
    fun `a red flag returns 409 and surfaces nothing`() =
        testApplication {
            application { module("jdbc:sqlite:${tempDb()}", testKey) }
            val response =
                client.post("/v1/plans/generate") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "medical_safety": {
                            "scoliosis_reported": true,
                            "red_flags": ["new_bowel_or_bladder_dysfunction"],
                            "current_curve_data_available": true,
                            "clinician_curve_specific_plan_available": true
                          }
                        }
                        """.trimIndent(),
                    )
                }
            // Red flag => pre-LLM gate closes generation => 409, no plan body.
            response.status shouldBe HttpStatusCode.Conflict
            val body = response.bodyAsText()
            body shouldContain "\"status\": \"blocked_red_flag\""
            body shouldContain "\"allow_training_generation\": false"
            body shouldNotContain "\"weekNumber\"" // no plan surfaced to the user
        }
}
