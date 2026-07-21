package dreamteam.domain.persistence

import kotlinx.serialization.json.JsonObject

/**
 * Persistence schema baseline. The PoC's JSON files under data/ already ship
 * `schema_version: "1.0.0"`; this fixes v1 for the backend durable store behind
 * the repository ports. There is NO on-device native database (ADR 0001):
 * offline-first state lives in the PWA (IndexedDB/localStorage); durable truth
 * lives here, behind [Repositories].
 */
object Schema {
    const val CURRENT: Int = 1
}

/**
 * One step in the document-migration ladder. Migrations for the versioned
 * document store are lazy/on-read transforms (the standard model for a document
 * store): a stored document carries its schemaVersion; the [DocumentMigrator]
 * walks registered steps from that version up to [Schema.CURRENT].
 *
 * v1 has no steps yet — the baseline IS v1. Adding a future migration = register
 * one [DocumentMigration]; no schema rewrite, no DB engine switch required.
 */
interface DocumentMigration {
    val fromVersion: Int
    val toVersion: Int
    fun migrate(raw: JsonObject): JsonObject
}

/**
 * Walks a stored document from [fromVersion] up to [Schema.CURRENT]. Aborts with
 * a clear error if a step is missing — silent data corruption is never an option
 * for health-signal data (data integrity is a product lens).
 */
class DocumentMigrator(private val steps: List<DocumentMigration> = emptyList()) {
    fun migrate(raw: JsonObject, fromVersion: Int): JsonObject {
        if (fromVersion == Schema.CURRENT) return raw
        require(fromVersion < Schema.CURRENT) {
            "Stored schema version $fromVersion is newer than code version ${Schema.CURRENT}; refusing to downgrade health data."
        }
        var current = raw
        var version = fromVersion
        while (version < Schema.CURRENT) {
            val step = steps.firstOrNull { it.fromVersion == version }
                ?: error("No migration step from schema version $version to ${version + 1}.")
            current = step.migrate(current)
            version = step.toVersion
        }
        return current
    }
}
