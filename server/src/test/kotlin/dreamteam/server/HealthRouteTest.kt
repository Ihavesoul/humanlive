package dreamteam.server

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

class HealthRouteTest {
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
}
