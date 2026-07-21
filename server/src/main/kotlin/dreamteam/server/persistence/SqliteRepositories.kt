package dreamteam.server.persistence

import dreamteam.domain.UserId
import dreamteam.domain.evidence.EvidenceSource
import dreamteam.domain.exercise.Exercise
import dreamteam.domain.nutrition.NutritionTarget
import dreamteam.domain.persistence.EvidenceSourceRepository
import dreamteam.domain.persistence.ExerciseRepository
import dreamteam.domain.persistence.NutritionRepository
import dreamteam.domain.persistence.ProgressRepository
import dreamteam.domain.persistence.SafetyRuleRepository
import dreamteam.domain.persistence.Schema
import dreamteam.domain.persistence.SymptomRepository
import dreamteam.domain.persistence.TrainingPlanRepository
import dreamteam.domain.persistence.UserRepository
import dreamteam.domain.progress.ProgressEntry
import dreamteam.domain.safety.SafetyRule
import dreamteam.domain.symptom.Symptom
import dreamteam.domain.training.TrainingPlan
import dreamteam.domain.user.User
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.sql.Connection
import java.sql.DriverManager

/**
 * Durable SQLite-backed repository ports. Replaces [InMemoryRepositories] for
 * the backend durable store (ADR 0003). One SQLite database file holds all
 * aggregates; each aggregate is stored as a JSON document (the
 * [dreamteam.domain.persistence.DocumentMigrator] model) plus a few indexed
 * columns for the by-user lookups the ports require.
 *
 * **Why document-store, not relational:** the domain aggregates are already
 * @Serializable and versioned ([Schema]); a column-per-field mapping would
 * duplicate the schema and break silently on every field addition, which is the
 * opposite of "protect health-signal data against silent corruption". A JSON
 * payload per row keeps the durable shape == the in-memory shape, and a future
 * [Schema] bump is a [DocumentMigrator] step, not a brittle ALTER TABLE.
 *
 * **Encryption posture:** SQLite here is the *durability* layer; at-rest
 * encryption for the backend is enforced at the infrastructure layer (managed
 * volume / database encryption), the appropriate level for a backend service
 * per SDD §8. The on-device client uses SQLCipher (native, M3). See ADR 0003.
 *
 * **Concurrency:** a single JDBC connection guarded by `synchronized`. SQLite
 * serializes writers anyway, and the backend is a single process — this is the
 * boring ceiling. per-account locks / a pool are the upgrade path if throughput
 * ever demands it.
 *
 * ponytail: single connection + synchronized — fine for one backend process;
 * swap for a pool (HikariCP) only if contention shows up.
 */
class SqliteStore(val dbPath: String) : AutoCloseable {
    internal val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    init {
        connection.createStatement().use { stmt ->
            // Schema baseline (Schema.CURRENT). A single source of truth for the
            // durable layout; bumped via Schema + DocumentMigrator, never ad hoc.
            stmt.execute("PRAGMA user_version = ${Schema.CURRENT}")
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            upsertMeta("schema_version", Schema.CURRENT.toString())
            stmt.execute("CREATE TABLE IF NOT EXISTS evidence_source(id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            stmt.execute("CREATE TABLE IF NOT EXISTS exercise(id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            stmt.execute("CREATE TABLE IF NOT EXISTS safety_rule(id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            stmt.execute("CREATE TABLE IF NOT EXISTS user(id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
            stmt.execute("CREATE TABLE IF NOT EXISTS training_plan(id TEXT PRIMARY KEY, user_id TEXT NOT NULL, payload TEXT NOT NULL)")
            stmt.execute("CREATE TABLE IF NOT EXISTS current_plan(user_id TEXT PRIMARY KEY, plan_id TEXT NOT NULL)")
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS progress(seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL, user_id TEXT NOT NULL, payload TEXT NOT NULL)",
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_progress_user ON progress(user_id, seq)")
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS symptom(seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL, user_id TEXT NOT NULL, payload TEXT NOT NULL)",
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_symptom_user ON symptom(user_id, seq)")
            stmt.execute("CREATE TABLE IF NOT EXISTS nutrition(user_id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        }
    }

    private fun upsertMeta(key: String, value: String) =
        connection.prepareStatement("INSERT INTO schema_meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")
            .use { it.setString(1, key); it.setString(2, value); it.executeUpdate() }

    fun <T> write(block: (Connection) -> T): T = synchronized(connection) { block(connection) }

    internal inline fun <reified T> encode(value: T): String = json.encodeToString(serializer(), value)
    internal inline fun <reified T> decode(payload: String): T = json.decodeFromString(serializer(), payload)

    override fun close() = connection.close()
}

/** Insert-or-replace a keyed JSON document; returns true if a row was written. */
private fun upsertDoc(conn: Connection, table: String, id: String, payload: String) {
    conn.prepareStatement("INSERT INTO $table(id,payload) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload")
        .use { it.setString(1, id); it.setString(2, payload); it.executeUpdate() }
}

private fun loadDoc(conn: Connection, table: String, id: String): String? =
    conn.prepareStatement("SELECT payload FROM $table WHERE id=?").use { ps ->
        ps.setString(1, id); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }

private fun loadAllDocs(conn: Connection, table: String): List<String> =
    conn.createStatement().use { it.executeQuery("SELECT payload FROM $table").use { rs ->
        buildList { while (rs.next()) add(rs.getString(1)) }
    } }

class SqliteEvidenceSourceRepository(private val store: SqliteStore) : EvidenceSourceRepository {
    override fun all(): List<EvidenceSource> = store.write { loadAllDocs(it, "evidence_source").map { p -> store.decode(p) } }
    override fun byId(id: String): EvidenceSource? = store.write { loadDoc(it, "evidence_source", id)?.let(store::decode) }
    override fun contains(id: String): Boolean = store.write { loadDoc(it, "evidence_source", id) != null }
    override fun save(source: EvidenceSource) { store.write { upsertDoc(it, "evidence_source", source.id, store.encode(source)) } }
}

class SqliteExerciseRepository(private val store: SqliteStore) : ExerciseRepository {
    override fun all(): List<Exercise> = store.write { loadAllDocs(it, "exercise").map { p -> store.decode(p) } }
    override fun byId(id: String): Exercise? = store.write { loadDoc(it, "exercise", id)?.let(store::decode) }
    override fun contains(id: String): Boolean = store.write { loadDoc(it, "exercise", id) != null }
    override fun save(exercise: Exercise) { store.write { upsertDoc(it, "exercise", exercise.id, store.encode(exercise)) } }
}

class SqliteSafetyRuleRepository(private val store: SqliteStore) : SafetyRuleRepository {
    override fun all(): List<SafetyRule> = store.write { loadAllDocs(it, "safety_rule").map { p -> store.decode(p) } }
    override fun byId(id: String): SafetyRule? = store.write { loadDoc(it, "safety_rule", id)?.let(store::decode) }
    override fun save(rule: SafetyRule) { store.write { upsertDoc(it, "safety_rule", rule.id, store.encode(rule)) } }
}

class SqliteUserRepository(private val store: SqliteStore) : UserRepository {
    override fun byId(id: String): User? = store.write { loadDoc(it, "user", id)?.let(store::decode) }
    override fun save(user: User) = store.write { upsertDoc(it, "user", user.id, store.encode(user)).let {} }
}

class SqliteTrainingPlanRepository(private val store: SqliteStore) : TrainingPlanRepository {
    override fun currentFor(userId: UserId): TrainingPlan? = store.write { conn ->
        conn.prepareStatement("SELECT plan_id FROM current_plan WHERE user_id=?").use { ps ->
            ps.setString(1, userId); ps.executeQuery().use { rs -> if (!rs.next()) null else loadDoc(conn, "training_plan", rs.getString(1)) }
        }?.let(store::decode)
    }
    override fun byId(id: String): TrainingPlan? = store.write { loadDoc(it, "training_plan", id)?.let(store::decode) }
    override fun save(plan: TrainingPlan) {
        store.write { conn ->
            conn.prepareStatement(
                "INSERT INTO training_plan(id,user_id,payload) VALUES(?,?,?) " +
                    "ON CONFLICT(id) DO UPDATE SET user_id=excluded.user_id, payload=excluded.payload",
            ).use { ps ->
                ps.setString(1, plan.id); ps.setString(2, plan.userId); ps.setString(3, store.encode(plan)); ps.executeUpdate()
            }
            conn.prepareStatement("INSERT INTO current_plan(user_id,plan_id) VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET plan_id=excluded.plan_id")
                .use { ps -> ps.setString(1, plan.userId); ps.setString(2, plan.id); ps.executeUpdate() }
        }
    }
}

class SqliteProgressRepository(private val store: SqliteStore) : ProgressRepository {
    override fun recentFor(userId: UserId, limit: Int): List<ProgressEntry> = store.write { conn ->
        conn.prepareStatement("SELECT payload FROM progress WHERE user_id=? ORDER BY seq DESC LIMIT ?").use { ps ->
            ps.setString(1, userId); ps.setInt(2, limit); ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(store.decode<ProgressEntry>(rs.getString(1))) }.reversed()
            }
        }
    }
    override fun append(entry: ProgressEntry) {
        store.write { conn ->
            conn.prepareStatement("INSERT INTO progress(id,user_id,payload) VALUES(?,?,?)").use { ps ->
                ps.setString(1, entry.id); ps.setString(2, entry.userId); ps.setString(3, store.encode(entry)); ps.executeUpdate()
            }
        }
    }
}

class SqliteSymptomRepository(private val store: SqliteStore) : SymptomRepository {
    override fun recentFor(userId: UserId, limit: Int): List<Symptom> = store.write { conn ->
        conn.prepareStatement("SELECT payload FROM symptom WHERE user_id=? ORDER BY seq DESC LIMIT ?").use { ps ->
            ps.setString(1, userId); ps.setInt(2, limit); ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(store.decode<Symptom>(rs.getString(1))) }.reversed()
            }
        }
    }
    override fun byId(id: String): Symptom? = store.write { conn ->
        conn.prepareStatement("SELECT payload FROM symptom WHERE id=? LIMIT 1").use { ps ->
            ps.setString(1, id); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }?.let(store::decode)
    override fun append(entry: Symptom) {
        store.write { conn ->
            conn.prepareStatement("INSERT INTO symptom(id,user_id,payload) VALUES(?,?,?)").use { ps ->
                ps.setString(1, entry.id); ps.setString(2, entry.userId); ps.setString(3, store.encode(entry)); ps.executeUpdate()
            }
        }
    }
}

class SqliteNutritionRepository(private val store: SqliteStore) : NutritionRepository {
    override fun currentFor(userId: UserId): NutritionTarget? = store.write { conn ->
        conn.prepareStatement("SELECT payload FROM nutrition WHERE user_id=?").use { ps ->
            ps.setString(1, userId); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }?.let(store::decode)
    override fun save(target: NutritionTarget) {
        store.write { conn ->
            conn.prepareStatement("INSERT INTO nutrition(user_id,payload) VALUES(?,?) ON CONFLICT(user_id) DO UPDATE SET payload=excluded.payload")
                .use { ps -> ps.setString(1, target.userId); ps.setString(2, store.encode(target)); ps.executeUpdate() }
        }
    }
}
