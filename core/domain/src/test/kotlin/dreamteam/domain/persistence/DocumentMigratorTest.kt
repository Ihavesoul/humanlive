package dreamteam.domain.persistence

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * DRE-6 "migrations baseline" proof: v1 is the baseline (no steps), the
 * migrator passes v1 through unchanged, registered steps walk old versions
 * forward, and a missing step / a future version refuses to corrupt data.
 */
class DocumentMigratorTest {
    @Test
    fun `v1 document passes through unchanged`() {
        val raw = buildJsonObject { put("schemaVersion", JsonPrimitive(1)); put("payload", JsonPrimitive("x")) }
        DocumentMigrator().migrate(raw, fromVersion = 1) shouldBe raw
    }

    @Test
    fun `a registered step walks v0 to v1`() {
        val step = object : DocumentMigration {
            override val fromVersion = 0
            override val toVersion = 1
            override fun migrate(raw: JsonObject) = buildJsonObject {
                put("schemaVersion", JsonPrimitive(1))
                put("migrated", JsonPrimitive(true))
            }
        }
        val out = DocumentMigrator(listOf(step)).migrate(JsonObject(emptyMap()), fromVersion = 0)
        out["migrated"]!!.jsonPrimitive.boolean shouldBe true
        out["schemaVersion"]!!.jsonPrimitive.int shouldBe 1
    }

    @Test
    fun `a missing step errors rather than silently dropping data`() {
        assertThrows<IllegalStateException> {
            DocumentMigrator(emptyList()).migrate(JsonObject(emptyMap()), fromVersion = 0)
        }
    }

    @Test
    fun `a future stored version refuses to downgrade`() {
        assertThrows<IllegalArgumentException> {
            DocumentMigrator().migrate(JsonObject(emptyMap()), fromVersion = Schema.CURRENT + 1)
        }
    }
}
