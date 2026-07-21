package dreamteam.server

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class RoutesTest {
    @Test
    fun `health endpoint reports ok`() =
        testApplication {
            application { module() }
            val response = client.get("/health")
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"status\""
            body shouldContain "\"ok\""
        }

    @Test
    fun `v1 safety evaluate locks side-specific content without clinician data`() =
        testApplication {
            application { module() }
            val response =
                client.post("/v1/safety/evaluate") {
                    contentType(ContentType.Application.Json)
                    // Matches medical_safety in workout_request.schema.json.
                    setBody(
                        """
                        {
                          "scoliosis_reported": true,
                          "red_flags": [],
                          "current_curve_data_available": false,
                          "clinician_curve_specific_plan_available": false
                        }
                        """.trimIndent(),
                    )
                }
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            body shouldContain "\"red_flag_gate_passed\": true"
            body shouldContain "\"allow_training_generation\": true"
            // Side-specific stays locked — the binding deterministic verdict.
            body shouldContain "\"allow_side_specific_content\": false"
            body shouldContain "Curve-specific correction remains locked"
        }

    @Test
    fun `v1 safety evaluate blocks when a red flag is reported`() =
        testApplication {
            application { module() }
            val response =
                client.post("/v1/safety/evaluate") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "scoliosis_reported": true,
                          "red_flags": ["new_bowel_or_bladder_dysfunction"],
                          "current_curve_data_available": true,
                          "curve_classification": "Lenke 1AN",
                          "clinician_curve_specific_plan_available": true
                        }
                        """.trimIndent(),
                    )
                }
            response.status shouldBe HttpStatusCode.OK
            val body = response.bodyAsText()
            // A red flag overrides even complete clinician data — code, not model.
            body shouldContain "\"red_flag_gate_passed\": false"
            body shouldContain "\"allow_training_generation\": false"
            body shouldContain "\"allow_side_specific_content\": false"
            body shouldContain "training generation blocked"
        }

    @Test
    fun `v1 plans generate surfaces the vetted baseline plan through the gateway`() =
        testApplication {
            application { module() }
            val response =
                client.post("/v1/plans/generate") {
                    contentType(ContentType.Application.Json)
                    // Scoliosis reported but NO red flags and NO current curve data:
                    // generic baseline surfaces; curve-specific content stays locked.
                    setBody(
                        """
                        {
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
            body shouldContain "\"schema_version\": \"1.0.0\""
            body shouldContain "\"status\": \"ok\""
            body shouldContain "\"red_flag_gate_passed\": true"
            // Side-specific correction stays locked (no current clinician curve data).
            body shouldContain "\"side_specific_content_enabled\": false"
            // The vetted baseline programme surfaces, evidence-linked per exercise.
            body shouldContain "\"session_id\": \"strength_A\""
            body shouldContain "\"exercise_id\": \"split_squat\""
            body shouldContain "\"evidence_ids\": ["
            // ADR 0001 #2: citations resolve from the allowlist, never invented.
            body shouldContain "\"evidence_id\": \"ACSM-RT-2026\""
        }

    @Test
    fun `v1 plans generate returns 409 blocked when a red flag is reported`() =
        testApplication {
            application { module() }
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
            response.status shouldBe HttpStatusCode.Conflict
            val body = response.bodyAsText()
            body shouldContain "\"status\": \"blocked_red_flag\""
            body shouldContain "\"red_flag_gate_passed\": false"
            // Nothing surfaces when the gate blocks — no partial guidance.
            body shouldContain "\"sessions\": []"
        }
}
